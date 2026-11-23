
package software.aporia.tracking.gateway;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.support.TestDatabase;
import com.zaxxer.hikari.HikariDataSource;

import software.aporia.tracking.protocol.SuntechAdapter;
import software.aporia.tracking.storage.JdbcDeviceLookup;
import software.aporia.tracking.storage.JdbcPacketIngestion;

class TcpClientHandlerIT {

    private HikariDataSource dataSource;
    private SessionRegistry sessionRegistry;
    private TcpClientHandler handler;
    private AtomicBoolean running;

    private UUID tenantId;
    private UUID deviceId;

    private final String identity = "9876543299";

    @BeforeEach
    @SuppressWarnings("unused") // Invocado pelo JUnit.
    void setUp() throws Exception {

        dataSource = TestDatabase.createDataSource();

        tenantId = UUID.randomUUID();
        deviceId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {

            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO tenants (id, name) VALUES (?, ?)"
                    )) {
                statement.setObject(1, tenantId);
                statement.setString(2, "TCP Integration Test");
                statement.executeUpdate();
            }

            try (PreparedStatement statement =
                    connection.prepareStatement(
                            """
                            INSERT INTO devices
                                (id, tenant_id, protocol, identity, enabled)
                            VALUES (?, ?, 'suntech', ?, true)
                            """
                    )) {
                statement.setObject(1, deviceId);
                statement.setObject(2, tenantId);
                statement.setString(3, identity);
                statement.executeUpdate();
            }
        }

        sessionRegistry = new SessionRegistry();
        running = new AtomicBoolean(true);

        handler = new TcpClientHandler(
                new JdbcPacketIngestion(dataSource),
                new JdbcDeviceLookup(dataSource),
                sessionRegistry,
                new SuntechAdapter(),
                running
        );
    }

    @AfterEach
    @SuppressWarnings("unused") // Invocado pelo JUnit.
    void tearDown() throws Exception {

        running.set(false);

        if (dataSource != null) {

            try (Connection connection = dataSource.getConnection()) {

                // Os testes geram pacotes relacionados ao dispositivo.
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM raw_packets WHERE device_id = ?"
                        )) {
                    statement.setObject(1, deviceId);
                    statement.executeUpdate();
                }

                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM devices WHERE id = ?"
                        )) {
                    statement.setObject(1, deviceId);
                    statement.executeUpdate();
                }

                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM tenants WHERE id = ?"
                        )) {
                    statement.setObject(1, tenantId);
                    statement.executeUpdate();
                }
            }

            dataSource.close();
        }
    }

    private String frame(String imei) {
        return "STT;" + imei
                + ";013831;20261120;08:45:00;"
                + "-16.6700;-49.2500;45.5;1\r";
    }

    private void send(Socket socket, String payload) throws Exception {
        OutputStream output = socket.getOutputStream();
        output.write(payload.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    @SuppressWarnings("SleepWhileInLoop") // Polling limitado pelo timeout do teste.
    private void awaitCondition(BooleanSupplier condition) {

        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    while (!condition.getAsBoolean()) {
                        Thread.sleep(10);
                    }
                }
        );
    }

    @Test
    void shouldRegisterAndRemoveSession() throws Exception {

        try (ServerSocket server = new ServerSocket(0);
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             Socket tracker = new Socket(
                     "127.0.0.1",
                     server.getLocalPort()
             );
             Socket gatewaySocket = server.accept()) {

            Future<?> task = executor.submit(
                    () -> handler.handle(gatewaySocket)
            );

            send(tracker, frame(identity));

            awaitCondition(() -> sessionRegistry.size() == 1);

            assertTrue(
                    sessionRegistry.find(tenantId, deviceId).isPresent()
            );

            tracker.shutdownOutput();

            task.get();

            assertEquals(0, sessionRegistry.size());
        }
    }

    @Test
    void shouldRejectIdentityChange() throws Exception {

        try (ServerSocket server = new ServerSocket(0);
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             Socket tracker = new Socket(
                     "127.0.0.1",
                     server.getLocalPort()
             );
             Socket gatewaySocket = server.accept()) {

            Future<?> task = executor.submit(
                    () -> handler.handle(gatewaySocket)
            );

            send(tracker, frame(identity));

            awaitCondition(() -> sessionRegistry.size() == 1);

            send(tracker, frame("0000000001"));

            task.get();

            assertEquals(0, sessionRegistry.size());
        }
    }

    @Test
    void shouldReplacePreviousConnection() throws Exception {

        try (ServerSocket server = new ServerSocket(0);
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             Socket tracker1 = new Socket(
                     "127.0.0.1",
                     server.getLocalPort()
             );
             Socket gateway1 = server.accept();
             Socket tracker2 = new Socket(
                     "127.0.0.1",
                     server.getLocalPort()
             );
             Socket gateway2 = server.accept()) {

            Future<?> task1 = executor.submit(
                    () -> handler.handle(gateway1)
            );

            send(tracker1, frame(identity));

            awaitCondition(() -> sessionRegistry.size() == 1);

            DeviceSession firstSession = sessionRegistry
                    .find(tenantId, deviceId)
                    .orElseThrow();

            Future<?> task2 = executor.submit(
                    () -> handler.handle(gateway2)
            );

            send(tracker2, frame(identity));

            awaitCondition(() ->
                    sessionRegistry.find(tenantId, deviceId)
                            .filter(session -> session != firstSession)
                            .isPresent()
            );

            assertFalse(firstSession.isConnected());
            assertTrue(gateway1.isClosed());

            tracker2.shutdownOutput();

            task1.get();
            task2.get();

            assertEquals(0, sessionRegistry.size());
        }
    }

    @Test
    void shouldRejectUnknownDevice() throws Exception {

        try (ServerSocket server = new ServerSocket(0);
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             Socket tracker = new Socket(
                     "127.0.0.1",
                     server.getLocalPort()
             );
             Socket gatewaySocket = server.accept()) {

            Future<?> task = executor.submit(
                    () -> handler.handle(gatewaySocket)
            );

            send(tracker, frame("0000000002"));

            task.get();

            assertEquals(0, sessionRegistry.size());
        }
    }
}
