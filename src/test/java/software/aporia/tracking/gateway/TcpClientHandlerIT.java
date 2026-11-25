
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

import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import software.aporia.tracking.observability.metrics.GatewayMetrics;
import software.aporia.tracking.protocol.suntech.SuntechAdapter;
import software.aporia.tracking.storage.device.JdbcDeviceLookup;
import software.aporia.tracking.storage.packet.JdbcPacketIngestion;
import software.aporia.tracking.support.TestDatabase;

/**
 * Exercita a descoberta e o ciclo de registro, substituição e encerramento de
 * sessões TCP com banco real.
 */
class TcpClientHandlerIT {

    /** Fonte de conexões JDBC usadas pela instância. */
    private HikariDataSource dataSource;
    /** Registry de métricas exclusivo do cenário. */
    private SimpleMeterRegistry meterRegistry;
    /** Registro que associa tenant e dispositivo à sessão atual. */
    private SessionRegistry sessionRegistry;
    /** Manipulador de conexão usado pelo teste com sockets locais. */
    private TcpClientHandler handler;
    /** Sinal de execução compartilhado com as tarefas do gateway. */
    private AtomicBoolean running;

    /** Tenant proprietário do cadastro do equipamento. */
    private UUID tenantId;
    /** Identificador interno do equipamento cadastrado. */
    private UUID deviceId;

    /** Identidade que acompanha os quadros do equipamento. */
    private final String identity = "9876543299";

    /**
     * Prepara o cadastro, o registro de sessões e o manipulador do socket.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @BeforeEach
    void setUp() throws Exception {

        meterRegistry = new SimpleMeterRegistry();
        var metrics = new GatewayMetrics(meterRegistry);
        dataSource = TestDatabase.createDataSource();

        tenantId = UUID.randomUUID();
        deviceId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {

            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tenants (id, name) VALUES (?, ?)")) {
                statement.setObject(1, tenantId);
                statement.setString(2, "TCP Integration Test");
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    """
                            INSERT INTO devices
                                (id, tenant_id, protocol, identity, enabled)
                            VALUES (?, ?, 'suntech', ?, true)
                            """)) {
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
                first -> new SuntechAdapter(),
                running,
                metrics);
    }

    /**
     * Interrompe o manipulador e remove os dados criados no cenário.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @AfterEach
    void tearDown() throws Exception {

        if (running != null) {
            running.set(false);
        }

        try {
            cleanUpDatabase();
        } finally {
            if (meterRegistry != null) {
                meterRegistry.close();
            }
        }
    }

    /** Remove os dados e fecha o pool JDBC do cenário. */
    private void cleanUpDatabase() throws Exception {
        if (dataSource != null) {

            try (Connection connection = dataSource.getConnection()) {

                // Os testes geram pacotes relacionados ao dispositivo.
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM raw_packets WHERE device_id = ?")) {
                    statement.setObject(1, deviceId);
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM devices WHERE id = ?")) {
                    statement.setObject(1, deviceId);
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM tenants WHERE id = ?")) {
                    statement.setObject(1, tenantId);
                    statement.executeUpdate();
                }
            }

            dataSource.close();
        }
    }

    /**
     * Constrói o quadro STT completo com a identidade solicitada.
     *
     * @param imei identidade declarada pelo rastreador no protocolo
     * @return mensagem STT de exemplo com terminador CR
     */
    private String frame(String imei) {
        return "STT;" + imei
                + ";013831;20261120;08:45:00;"
                + "-16.6700;-49.2500;45.5;1\r";
    }

    /**
     * Grava os bytes ASCII no socket e descarrega o buffer.
     *
     * @param socket  socket TCP da conexão do equipamento
     * @param payload bytes do conteúdo da mensagem
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private void send(Socket socket, String payload) throws Exception {
        OutputStream output = socket.getOutputStream();
        output.write(payload.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    /**
     * Aguarda uma mudança de sessão observável dentro do prazo do teste.
     *
     * @param condition condição observada até o prazo do cenário
     */
    @SuppressWarnings("SleepWhileInLoop") // Polling limitado pelo timeout do teste.
    private void awaitCondition(BooleanSupplier condition) {

        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    while (!condition.getAsBoolean()) {
                        Thread.sleep(10);
                    }
                });
    }

    /**
     * Acompanha registro e remoção da sessão durante uma conexão real.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRegisterAndRemoveSession() throws Exception {

        try (ServerSocket server = new ServerSocket(0);
                var executor = Executors.newVirtualThreadPerTaskExecutor();
                Socket tracker = new Socket(
                        "127.0.0.1",
                        server.getLocalPort());
                Socket gatewaySocket = server.accept()) {

            Future<?> task = executor.submit(
                    () -> handler.handle(gatewaySocket));

            send(tracker, frame(identity));

            awaitCondition(() -> sessionRegistry.size() == 1);

            assertTrue(
                    sessionRegistry.find(tenantId, deviceId).isPresent());

            tracker.shutdownOutput();

            task.get();

            assertEquals(0, sessionRegistry.size());
        }
    }

    /**
     * Confere a recomposição do quadro entre leituras sem perder a admissão da
     * sessão.
     */
    @Test
    void shouldRegisterAfterFragmentedFrame() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
                var executor = Executors.newVirtualThreadPerTaskExecutor();
                Socket tracker = new Socket("127.0.0.1", server.getLocalPort());
                Socket gateway = server.accept()) {
            Future<?> task = executor.submit(() -> handler.handle(gateway));
            send(tracker, "STT;");
            assertEquals(0, sessionRegistry.size());
            send(tracker, frame(identity).substring(4));
            awaitCondition(() -> sessionRegistry.find(tenantId, deviceId).isPresent());
            tracker.shutdownOutput();
            task.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, sessionRegistry.size());
        }
    }

    /** Um cadastro desabilitado não pode publicar sessão nem persistir pacotes. */
    @Test
    void shouldRejectDisabledDevice() throws Exception {
        try (var connection = dataSource.getConnection();
                var update = connection.prepareStatement(
                        "UPDATE devices SET enabled=false WHERE tenant_id=? AND id=?")) {
            update.setObject(1, tenantId);
            update.setObject(2, deviceId);
            assertEquals(1, update.executeUpdate());
        }
        try (ServerSocket server = new ServerSocket(0);
                var executor = Executors.newVirtualThreadPerTaskExecutor();
                Socket tracker = new Socket("127.0.0.1", server.getLocalPort());
                Socket gateway = server.accept()) {
            Future<?> task = executor.submit(() -> handler.handle(gateway));
            send(tracker, frame(identity));
            task.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, sessionRegistry.size());
            try (var connection = dataSource.getConnection();
                    var query = connection.prepareStatement(
                            "SELECT count(*) FROM raw_packets WHERE tenant_id=? AND device_id=?")) {
                query.setObject(1, tenantId);
                query.setObject(2, deviceId);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
        }
    }

    /**
     * Verifica o encerramento ao receber outra identidade na mesma conexão.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectIdentityChange() throws Exception {

        try (ServerSocket server = new ServerSocket(0);
                var executor = Executors.newVirtualThreadPerTaskExecutor();
                Socket tracker = new Socket(
                        "127.0.0.1",
                        server.getLocalPort());
                Socket gatewaySocket = server.accept()) {

            Future<?> task = executor.submit(
                    () -> handler.handle(gatewaySocket));

            send(tracker, frame(identity));

            awaitCondition(() -> sessionRegistry.size() == 1);

            send(tracker, frame("0000000001"));

            task.get();

            assertEquals(0, sessionRegistry.size());
        }
    }

    /**
     * Reconecta o mesmo dispositivo e confere a substituição da conexão anterior.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldReplacePreviousConnection() throws Exception {

        try (ServerSocket server = new ServerSocket(0);
                var executor = Executors.newVirtualThreadPerTaskExecutor();
                Socket tracker1 = new Socket(
                        "127.0.0.1",
                        server.getLocalPort());
                Socket gateway1 = server.accept();
                Socket tracker2 = new Socket(
                        "127.0.0.1",
                        server.getLocalPort());
                Socket gateway2 = server.accept()) {

            Future<?> task1 = executor.submit(
                    () -> handler.handle(gateway1));

            send(tracker1, frame(identity));

            awaitCondition(() -> sessionRegistry.size() == 1);

            DeviceSession firstSession = sessionRegistry
                    .find(tenantId, deviceId)
                    .orElseThrow();

            Future<?> task2 = executor.submit(
                    () -> handler.handle(gateway2));

            send(tracker2, frame(identity));

            awaitCondition(() -> sessionRegistry.find(tenantId, deviceId)
                    .filter(session -> session != firstSession)
                    .isPresent());

            assertFalse(firstSession.isConnected());
            assertTrue(gateway1.isClosed());

            tracker2.shutdownOutput();

            task1.get();
            task2.get();

            assertEquals(0, sessionRegistry.size());
        }
    }

    /**
     * Confere os metadados pendentes do Suntech e a ausência de sessão autorizada.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldDiscoverUnknownSuntechWithoutStartingSession() throws Exception {

        try (ServerSocket server = new ServerSocket(0);
                var executor = Executors.newVirtualThreadPerTaskExecutor();
                Socket tracker = new Socket(
                        "127.0.0.1",
                        server.getLocalPort());
                Socket gatewaySocket = server.accept()) {

            Future<?> task = executor.submit(
                    () -> handler.handle(gatewaySocket));

            send(tracker, frame("0000000002").replace("013831;", "013833;201;"));

            task.get();

            try (var connection = dataSource.getConnection();
                    var query = connection.prepareStatement("""
                            SELECT manufacturer, wire_format, reported_model FROM pending_device_discoveries
                            WHERE protocol='suntech' AND identity='0000000002'
                            """);
                    var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("Suntech", rows.getString(1));
                assertEquals("Suntech ASCII", rows.getString(2));
                assertEquals("201", rows.getString(3));
            }
            try (var connection = dataSource.getConnection();
                    var cleanup = connection.prepareStatement(
                            "DELETE FROM device_discoveries WHERE protocol='suntech' AND identity='0000000002'")) {
                assertEquals(1, cleanup.executeUpdate());
            }
            assertEquals(0, sessionRegistry.size());
        }
    }
}
