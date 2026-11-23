package software.aporia.tracking.gateway;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.aporia.tracking.command.dispatcher.CommandDispatcher;
import software.aporia.tracking.command.dispatcher.DispatchOwner;
import software.aporia.tracking.protocol.ProtocolAdapter;
import software.aporia.tracking.protocol.ProtocolRegistry;
import software.aporia.tracking.protocol.suntech.SuntechAdapter;
import software.aporia.tracking.protocol.teltonika.TeltonikaAdapter;
import software.aporia.tracking.storage.Database;
import software.aporia.tracking.storage.command.DeviceCommandDispatchRepository;
import software.aporia.tracking.storage.device.JdbcDeviceLookup;
import software.aporia.tracking.storage.packet.JdbcPacketIngestion;

/**
 * Inicia o servidor TCP, distribui conexões entre tarefas e, quando configurado, executa o
 * dispatcher junto às sessões.
 */
public class TcpGateway {

    private static final Logger log
            = LoggerFactory.getLogger(TcpGateway.class);

    private static final int PORT = Integer.parseInt(
            System.getenv().getOrDefault("TRACKING_TCP_PORT", "5500")
    );

    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;

    /**
     * Abre o listener, instala a rotina de encerramento e inicia o envio periódico se houver
     * proprietário configurado.
     */
    public static void main(String[] args) throws IOException {

        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch gatewayFinished = new CountDownLatch(1);

        Set<Socket> activeClients
                = ConcurrentHashMap.newKeySet();

        String ownerPath = System.getenv("TRACKING_DISPATCH_OWNER_FILE");
        try (
                var dataSource = Database.createDataSource(); ServerSocket server = new ServerSocket(
                PORT,
                50,
                InetAddress.getByName(System.getenv().getOrDefault("TRACKING_TCP_BIND", "0.0.0.0"))
        ); var executor = Executors.newVirtualThreadPerTaskExecutor();
                var owner = ownerPath == null ? null : DispatchOwner.open(Path.of(ownerPath));
                var polling = Executors.newSingleThreadScheduledExecutor()) {

            JdbcPacketIngestion ingestion
                    = new JdbcPacketIngestion(dataSource);

            JdbcDeviceLookup deviceLookup
                    = new JdbcDeviceLookup(dataSource);

            SessionRegistry sessionRegistry
                    = new SessionRegistry();

            ProtocolRegistry registry = new ProtocolRegistry(
                    List.of(new SuntechAdapter(), new TeltonikaAdapter())
            );

            String protocolId = System.getenv()
                    .getOrDefault("TRACKING_PROTOCOL", "auto");

            java.util.function.IntFunction<ProtocolAdapter> select = registry::detect;
            if (!"auto".equals(protocolId)) {
                var fixed = registry.get(protocolId);
                select = first -> fixed;
            }

            TcpClientHandler clientHandler = new TcpClientHandler(
                    ingestion,
                    deviceLookup,
                    sessionRegistry,
                    select,
                    running
            );

            log.info("TCP listener configured for {}", protocolId);

            if (owner != null) {
                var dispatcher = new CommandDispatcher(
                        dataSource, new DeviceCommandDispatchRepository(),
                        new SessionCommandTransport(sessionRegistry), owner);
                polling.scheduleWithFixedDelay(() -> poll(dispatcher, sessionRegistry, running),
                        0, 1, TimeUnit.SECONDS);
            }
            Thread shutdownHook = new Thread(
                    () -> shutdown(server, running, gatewayFinished, activeClients),
                    "tracking-gateway-shutdown");

            Runtime.getRuntime().addShutdownHook(shutdownHook);

            try {

                log.info(
                        "Tracking TCP Gateway listening on {}:{}",
                        server.getInetAddress().getHostAddress(), server.getLocalPort()
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

    private static void shutdown(ServerSocket server, AtomicBoolean running,
                                 CountDownLatch gatewayFinished, Set<Socket> activeClients) {
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
    }

    private static void poll(CommandDispatcher dispatcher,
                             SessionRegistry sessions, AtomicBoolean running) {
        if (!running.get()) return;
        try {
            dispatcher.recover();
            for (DeviceSession session : sessions.onlineSessions()) {
                if (!running.get()) break;
                dispatcher.dispatchNext(session.tenantId(), session.deviceId());
            }
        } catch (java.sql.SQLException error) {
            log.error("Command polling failed, SQLState={}", error.getSQLState());
        } catch (RuntimeException error) {
            log.error("Command polling failed", error);
        }
    }
}
