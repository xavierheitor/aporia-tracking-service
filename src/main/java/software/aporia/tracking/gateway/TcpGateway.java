package software.aporia.tracking.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.aporia.tracking.core.ReceivedFrame;
import software.aporia.tracking.protocol.ProtocolAdapter;
import software.aporia.tracking.protocol.ProtocolFrame;
import software.aporia.tracking.protocol.ProtocolRegistry;
import software.aporia.tracking.protocol.ProtocolSession;
import software.aporia.tracking.protocol.SuntechAdapter;
import software.aporia.tracking.storage.Database;
import software.aporia.tracking.storage.JdbcDeviceLookup;
import software.aporia.tracking.storage.JdbcPacketIngestion;
import software.aporia.tracking.storage.RegisteredDevice;

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
                                    handleClient(
                                            client,
                                            ingestion,
                                            running,
                                            adapter,
                                            deviceLookup,
                                            sessionRegistry
                                    );
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

    private static void handleClient(
            Socket client,
            JdbcPacketIngestion ingestion,
            AtomicBoolean running,
            ProtocolAdapter adapter,
            JdbcDeviceLookup deviceLookup,
            SessionRegistry sessionRegistry
    ) {

        log.debug(
                "TCP connection accepted: remote={}",
                client.getRemoteSocketAddress()
        );

        ProtocolSession session = adapter.newSession();
        DeviceSession registeredSession = null;

        try (
                client;
                InputStream input = client.getInputStream()) {

            client.setSoTimeout(READ_TIMEOUT_MS);

            byte[] buffer = new byte[4096];
            int bytesRead;

            while ((bytesRead = input.read(buffer)) != -1) {

                byte[] received
                        = Arrays.copyOf(buffer, bytesRead);

                for (ProtocolFrame frame : session.receive(received)) {

                    ReceivedFrame receivedFrame
                            = new ReceivedFrame(
                                    adapter.protocolId(),
                                    frame.deviceIdentity(),
                                    frame.payload(),
                                    adapter.decoderVersion()
                            );
                    if (registeredSession != null) {
                        registeredSession.validateIdentity(
                                frame.deviceIdentity()
                        );
                    }

                    var packetId = ingestion.accept(receivedFrame);
                    if (registeredSession == null) {

                        RegisteredDevice device = deviceLookup.findEnabled(
                                adapter.protocolId(),
                                frame.deviceIdentity()
                        ).orElseThrow(()
                                -> new IllegalArgumentException(
                                        "UNKNOWN_OR_DISABLED_DEVICE"
                                )
                        );

                        registeredSession = new DeviceSession(
                                device.tenantId(),
                                device.deviceId(),
                                device.protocol(),
                                device.identity(),
                                client
                        );

                        sessionRegistry.register(registeredSession);

                        log.info(
                                "Device session registered: tenantId={}, deviceId={}",
                                device.tenantId(),
                                device.deviceId()
                        );
                    }

                    log.info(
                            "Packet persisted: id={}, protocol={}, identity={}, bytes={}",
                            packetId,
                            adapter.protocolId(),
                            frame.deviceIdentity(),
                            frame.payload().length
                    );
                }
            }

        } catch (SocketTimeoutException e) {

            log.debug("TCP session timed out");

        } catch (IllegalArgumentException e) {

            log.warn(
                    "Invalid Suntech frame: {}",
                    e.getMessage()
            );

        } catch (IOException e) {

            if (!running.get() && e instanceof SocketException) {
                log.debug(
                        "TCP connection closed during gateway shutdown"
                );
            } else {
                log.warn("TCP connection error", e);
            }

        } catch (IllegalStateException e) {

            log.error("Packet persistence failed", e);
        } finally {
            if (registeredSession != null) {
                sessionRegistry.unregister(registeredSession);
            }
        }
    }
}
