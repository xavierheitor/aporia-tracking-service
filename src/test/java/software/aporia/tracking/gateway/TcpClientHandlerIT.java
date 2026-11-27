
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
 * Protege a descoberta, a admissão, a substituição e o encerramento de sessões TCP no handler
 * com PostgreSQL real.
 *
 * <p>
 * Cada cenário usa o schema isolado por JVM de {@link TestDatabase}, um cadastro próprio e
 * sockets de loopback. O handler roda em uma virtual thread enquanto o teste simula o
 * rastreador; prazos limitados tornam visível uma sessão que não é registrada, substituída ou
 * removida. Os dados são apagados ao final para não interferir nos demais testes.
 * </p>
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
     * Cenário: banco de teste vazio. Ação: criar tenant e dispositivo habilitado, montar o
     * registro de sessões e o handler. Resultado: cada cenário parte de um cadastro único e de
     * um handler com métricas próprias.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @BeforeEach
    void setUp() throws Exception {

        // Métricas e pool são exclusivos do cenário; a limpeza não afeta outras suítes.
        meterRegistry = new SimpleMeterRegistry();
        var metrics = new GatewayMetrics(meterRegistry);
        dataSource = TestDatabase.createDataSource();

        // Tenant e dispositivo novos por teste; a identidade é fixa para o formato Suntech.
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

        // O handler compartilha registro e sinal de execução com as tarefas do teste.
        handler = new TcpClientHandler(
                new JdbcPacketIngestion(dataSource),
                new JdbcDeviceLookup(dataSource),
                sessionRegistry,
                _ -> new SuntechAdapter(),
                running,
                metrics);
    }

    /**
     * Cenário: recursos criados pelo cenário. Ação: sinalizar a parada do handler e remover os
     * dados. Resultado: o registro de métricas é fechado mesmo se a limpeza falhar.
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

    /**
     * Cenário: dados de tenant, dispositivo e pacotes criados pelo teste. Ação: apagar os
     * dependentes e os pais na ordem das chaves estrangeiras e fechar o pool. Resultado: o
     * schema isolado fica sem resíduos do cenário.
     *
     * @throws Exception se a preparação ou a verificação falhar
     */
    private void cleanUpDatabase() throws Exception {
        if (dataSource != null) {

            try (Connection connection = dataSource.getConnection()) {

                // Os testes geram pacotes que referenciam o dispositivo; dependentes vêm antes dos pais.
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
     * Cenário: identidade de rastreador que deve constar no quadro. Ação: montar uma mensagem
     * STT completa. Resultado: quadro ASCII terminado em CR, com data e coordenadas fixas.
     *
     * @param imei identidade declarada pelo rastreador no protocolo
     * @return mensagem STT de exemplo com terminador CR
     */
    private String frame(String imei) {
        // A data e as coordenadas fixas tornam o quadro determinístico para o decoder Suntech.
        return "STT;" + imei
                + ";013831;20261120;08:45:00;"
                + "-16.6700;-49.2500;45.5;1\r";
    }

    /**
     * Cenário: socket do rastreador conectado ao handler. Ação: escrever o payload ASCII e
     * descarregar o buffer. Resultado: o handler recebe o quadro completo nesta leitura.
     *
     * @param socket  socket TCP da conexão do equipamento
     * @param payload bytes do conteúdo da mensagem
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private void send(Socket socket, String payload) throws Exception {
        OutputStream output = socket.getOutputStream();
        output.write(payload.getBytes(StandardCharsets.US_ASCII));
        // flush() garante que o handler veja o quadro sem esperar o fechamento do socket.
        output.flush();
    }

    /**
     * Cenário: condição observável que muda de forma assíncrona. Ação: repetir a consulta a
     * cada 10 ms. Resultado: retorno assim que a condição ocorrer ou falha preemptiva após
     * cinco segundos.
     *
     * @param condition condição observada até o prazo do cenário
     */
    private void awaitCondition(BooleanSupplier condition) {

        // Polling limitado pelo timeout: a execução preemptiva interrompe o laço no prazo.
        assertTimeoutPreemptively(
                java.util.Objects.requireNonNull(Duration.ofSeconds(5)),
                () -> {
                    while (!condition.getAsBoolean()) {
                        Thread.sleep(10);
                    }
                });
    }

    /**
     * Cenário: dispositivo habilitado e uma conexão de loopback. Ação: enviar um quadro STT e
     * encerrar a saída do rastreador. Resultado: a sessão é registrada e removida ao final do
     * handler, deixando o registro vazio.
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

            // O handler atende o lado aceito; o teste escreve pelo lado do rastreador.
            Future<?> task = executor.submit(
                    () -> handler.handle(gatewaySocket));

            // O STT identifica o dispositivo já cadastrado e dispara a admissão da sessão.
            send(tracker, frame(identity));

            awaitCondition(() -> sessionRegistry.size() == 1);

            assertTrue(
                    sessionRegistry.find(tenantId, deviceId).isPresent());

            tracker.shutdownOutput();

            // EOF encerra o handler, cujo finally remove somente a própria sessão.
            task.get();

            assertEquals(0, sessionRegistry.size());
        }
    }

    /**
     * Cenário: quadro STT dividido em duas leituras TCP. Ação: enviar o prefixo e depois o
     * restante do mesmo quadro. Resultado: a sessão é admitida e removida como no quadro
     * inteiro, sem perder a identidade.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRegisterAfterFragmentedFrame() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
                var executor = Executors.newVirtualThreadPerTaskExecutor();
                Socket tracker = new Socket("127.0.0.1", server.getLocalPort());
                Socket gateway = server.accept()) {
            Future<?> task = executor.submit(() -> handler.handle(gateway));
            // O primeiro fragmento não fecha o quadro; o enquadrador o mantém no buffer.
            send(tracker, "STT;");
            assertEquals(0, sessionRegistry.size());
            // O restante completa o STT e permite a admissão da sessão.
            send(tracker, frame(identity).substring(4));
            awaitCondition(() -> sessionRegistry.find(tenantId, deviceId).isPresent());
            tracker.shutdownOutput();
            task.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, sessionRegistry.size());
        }
    }

    /**
     * Cenário: dispositivo existente marcado como desabilitado antes da conexão. Ação: enviar um
     * quadro STT. Resultado: o handler encerra sem publicar sessão e nenhum pacote é persistido.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectDisabledDevice() throws Exception {
        try (var connection = dataSource.getConnection();
                var update = connection.prepareStatement(
                        "UPDATE devices SET enabled=false WHERE tenant_id=? AND id=?")) {
            update.setObject(1, tenantId);
            update.setObject(2, deviceId);
            // A desabilitação precisa ser confirmada antes de abrir o socket do cenário.
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
            // Sem admissão, a ingestão não pode gravar pacotes para o dispositivo.
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
     * Cenário: sessão admitida com a identidade do cadastro. Ação: enviar outro quadro com
     * identidade diferente na mesma conexão. Resultado: o handler encerra e a sessão é
     * removida do registro.
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

            // A primeira sessão é admitida com a identidade do cadastro.
            send(tracker, frame(identity));

            awaitCondition(() -> sessionRegistry.size() == 1);

            // A identidade divergente viola o vínculo e encerra o handler.
            send(tracker, frame("0000000001"));

            task.get();

            assertEquals(0, sessionRegistry.size());
        }
    }

    /**
     * Cenário: duas conexões simultâneas do mesmo dispositivo habilitado. Ação: enviar um STT
     * pela segunda. Resultado: a primeira sessão é invalidada, seu socket é fechado e apenas a
     * segunda permanece no registro.
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

            // As duas conexões do mesmo dispositivo ficam abertas ao mesmo tempo.
            Future<?> task1 = executor.submit(
                    () -> handler.handle(gateway1));

            send(tracker1, frame(identity));

            awaitCondition(() -> sessionRegistry.size() == 1);

            DeviceSession firstSession = sessionRegistry
                    .find(tenantId, deviceId)
                    .orElseThrow();

            Future<?> task2 = executor.submit(
                    () -> handler.handle(gateway2));

            // A segunda admissão substitui a primeira e fecha o socket antigo.
            send(tracker2, frame(identity));

            awaitCondition(() -> sessionRegistry.find(tenantId, deviceId)
                    .filter(session -> session != firstSession)
                    .isPresent());

            assertFalse(firstSession.isConnected());
            assertTrue(gateway1.isClosed());

            tracker2.shutdownOutput();

            // Encerrar a segunda remove a sessão; a primeira já saiu pelo fechamento.
            task1.get();
            task2.get();

            assertEquals(0, sessionRegistry.size());
        }
    }

    /**
     * Cenário: quadro STT de identidade sem cadastro. Ação: enviar o quadro e consultar a
     * descoberta pendente. Resultado: metadados Suntech persistidos, nenhuma sessão autorizada
     * e a limpeza remove o registro para os demais cenários.
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

            // Identidade fora do cadastro: o primeiro quadro vira descoberta, sem autorização.
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
                // O mapa 013833 anuncia o modelo no campo seguinte, preenchido com "201".
                assertEquals("201", rows.getString(3));
            }
            try (var connection = dataSource.getConnection();
                    var cleanup = connection.prepareStatement(
                            "DELETE FROM device_discoveries WHERE protocol='suntech' AND identity='0000000002'")) {
                // A view de pendências lê a tabela base; a limpeza evita resíduo no schema.
                assertEquals(1, cleanup.executeUpdate());
            }
            assertEquals(0, sessionRegistry.size());
        }
    }
}
