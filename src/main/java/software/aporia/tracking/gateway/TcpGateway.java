package software.aporia.tracking.gateway;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.aporia.tracking.protocol.ProtocolAdapter;
import software.aporia.tracking.protocol.ProtocolRegistry;
import software.aporia.tracking.protocol.SuntechAdapter;
import software.aporia.tracking.storage.Database;
import software.aporia.tracking.storage.JdbcDeviceLookup;
import software.aporia.tracking.storage.JdbcPacketIngestion;

public class TcpGateway {

    private static final Logger log
            = LoggerFactory.getLogger(TcpGateway.class);

    private static final int PORT = Integer.parseInt(
            System.getenv().getOrDefault("TRACKING_TCP_PORT", "5500")
    );

    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;

    public static void main(String[] args) throws IOException {

        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch gatewayFinished = new CountDownLatch(1);

        Set<Socket> activeClients
                = ConcurrentHashMap.newKeySet();

        try (
                var dataSource = Database.createDataSource(); ServerSocket server = new ServerSocket(
                PORT,
                50,
                InetAddress.getByName("0.0.0.0")
        ); var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            JdbcPacketIngestion ingestion
                    = new JdbcPacketIngestion(dataSource);

            JdbcDeviceLookup deviceLookup
                    = new JdbcDeviceLookup(dataSource);

            SessionRegistry sessionRegistry
                    = new SessionRegistry();

            ProtocolRegistry registry = new ProtocolRegistry(
                    List.of(new SuntechAdapter())
            );

            String protocolId = System.getenv()
                    .getOrDefault("TRACKING_PROTOCOL", "suntech");

            ProtocolAdapter adapter = registry.get(protocolId);

            TcpClientHandler clientHandler = new TcpClientHandler(
                    ingestion,
                    deviceLookup,
                    sessionRegistry,
                    adapter,
                    running
            );

            log.info(
                    "TCP listener configured: protocol={}, decoder={}",
                    adapter.protocolId(),
                    adapter.decoderVersion()
            );

            Thread shutdownHook = new Thread(() -> {

                log.info("Shutdown requested for TCP Gateway");
                running.set(false);

                try {
                    server.close();
                } catch (IOException e) {
                    log.warn("Error closing TCP listener", e);
                }

                try {
                    if (!gatewayFinished.await(
                            SHUTDOWN_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    )) {
                        log.warn("Gateway shutdown timeout exceeded");

                        for (Socket client : activeClients) {
                            try {
                                client.close();
                            } catch (IOException e) {
                                log.debug(
                                        "Error closing client socket",
                                        e
                                );
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

            }, "tracking-gateway-shutdown");

            Runtime.getRuntime().addShutdownHook(shutdownHook);

            try {

                log.info(
                        "Tracking TCP Gateway listening on 0.0.0.0:{}",
                        PORT
                );

                while (running.get()) {

                    try {
                        Socket client = server.accept();

                        if (!running.get()) {
                            client.close();
                            break;
                        }

                        activeClients.add(client);

                        try {
                            executor.submit(() -> {
                                try {
                                    clientHandler.handle(client);
                                } finally {
                                    activeClients.remove(client);
                                }
                            });

                        } catch (RuntimeException e) {
                            activeClients.remove(client);
                            client.close();
                            throw e;
                        }

                    } catch (SocketException e) {

                        if (!running.get()) {
                            break;
                        }

                        throw e;
                    }
                }

            } finally {

                log.info("TCP Gateway stopping");

                // Desbloqueia as leituras TCP das sessões ativas.
                for (Socket client : activeClients) {
                    try {
                        client.close();
                    } catch (IOException e) {
                        log.debug(
                                "Error closing client socket",
                                e
                        );
                    }
                }
            }

            // O try-with-resources aguarda as virtual threads
            // antes de fechar o DataSource.
        } finally {
            gatewayFinished.countDown();
            log.info("TCP Gateway stopped");
        }
    }

}
