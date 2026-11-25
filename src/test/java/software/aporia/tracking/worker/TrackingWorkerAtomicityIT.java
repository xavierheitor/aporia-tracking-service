package software.aporia.tracking.worker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import software.aporia.tracking.observability.metrics.WorkerMetrics;
import software.aporia.tracking.protocol.DecoderRegistry;
import software.aporia.tracking.protocol.suntech.SuntechDecoder;
import software.aporia.tracking.protocol.teltonika.TeltonikaAvl;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

/** Provoca falha na outbox e verifica o rollback das gravações anteriores na mesma transação. */
class TrackingWorkerAtomicityIT {

    private SimpleMeterRegistry meterRegistry;
    private WorkerMetrics metrics;
    private DecoderRegistry decoderRegistry;

    @BeforeEach
    void setUpWorkerDependencies() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new WorkerMetrics(meterRegistry);
        decoderRegistry = new DecoderRegistry(List.of(
                new SuntechDecoder(), new TeltonikaAvl()));
    }

    @AfterEach
    void closeMeterRegistry() {
        meterRegistry.close();
    }

    /**
     * Provoca uma falha ao gravar o evento e confere o rollback dos efeitos anteriores.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRollbackEverythingWhenOutboxInsertFails()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource,
                "1234567890"
        )) {
            UUID packetId = UUID.randomUUID();

            byte[] payload = ("STT;1234567890;013831;20261120;08:45:00;"
                    + "-16.6700;-49.2500;45.5;1\r").getBytes(StandardCharsets.US_ASCII);

            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(payload)
            );

            // 1. Inserir pacote no banco de testes.
            try (
                    Connection connection = dataSource.getConnection(); PreparedStatement statement
                    = connection.prepareStatement("""
                        INSERT INTO raw_packets (
                            id,
                            tenant_id,
                            device_id,
                            payload,
                            payload_hash,
                            decoder_version,
                            correlation_id
                        )
                        VALUES (
                            ?, ?, ?, ?, ?,
                            'suntech-ascii-v3', ?
                        )
                        """)) {
                statement.setObject(1, packetId);
                statement.setObject(2, fixtures.tenantId());
                statement.setObject(3, fixtures.deviceId());
                statement.setBytes(4, payload);
                statement.setString(5, hash);
                statement.setObject(6, UUID.randomUUID());

                statement.executeUpdate();
            }

            // 2. Criar nomes exclusivos para os objetos SQL.
            String suffix = UUID.randomUUID()
                    .toString()
                    .replace("-", "");

            String functionName = "test_fail_outbox_fn_" + suffix;
            String triggerName = "test_fail_outbox_trg_" + suffix;

            try {
                createFailureTrigger(
                        dataSource,
                        functionName,
                        triggerName,
                        fixtures.tenantId()
                );

                // 3. Executar o worker.
                TrackingWorker worker = new TrackingWorker(
                        dataSource,
                        fixtures.tenantId(), decoderRegistry, metrics);

                IllegalStateException exception = assertThrows(
                        IllegalStateException.class,
                        worker::processOne
                );

                // A falha SQL deve ser a causa da exceção.
                SQLException sqlException = assertInstanceOf(
                        SQLException.class,
                        exception.getCause()
                );

                assertTrue(
                        sqlException.getMessage()
                                .contains("SIMULATED_OUTBOX_FAILURE")
                );

                // 4. Verificar que todas as operações
                // realizadas na transação foram revertidas.
                try (Connection connection
                        = dataSource.getConnection()) {

                    // A mensagem não deve existir.
                    assertEquals(
                            0,
                            count(connection, """
                            SELECT COUNT(*)
                            FROM messages
                            WHERE raw_packet_id = ?
                            """, packetId)
                    );

                    // A posição não deve existir.
                    assertEquals(
                            0,
                            count(connection, """
                            SELECT COUNT(*)
                            FROM positions
                            WHERE tenant_id = ?
                            """, fixtures.tenantId())
                    );

                    // A última posição não deve existir.
                    assertEquals(
                            0,
                            count(connection, """
                            SELECT COUNT(*)
                            FROM last_positions
                            WHERE tenant_id = ?
                            """, fixtures.tenantId())
                    );

                    // O evento não deve existir.
                    assertEquals(
                            0,
                            count(connection, """
                            SELECT COUNT(*)
                            FROM outbox_events
                            WHERE tenant_id = ?
                            """, fixtures.tenantId())
                    );

                    // O pacote deve continuar pendente,
                    // sem registrar tentativa concluída.
                    assertEquals(
                            1,
                            count(connection, """
                            SELECT COUNT(*)
                            FROM raw_packets
                            WHERE id = ?
                              AND status = 'pending'
                              AND attempts = 0
                            """, packetId)
                    );
                }

            } finally {
                // 5. Remover os objetos criados pelo teste.
                dropFailureTrigger(
                        dataSource,
                        functionName,
                        triggerName
                );
            }
        }
    }

    /**
     * Cria uma trigger que provoca falha somente para eventos pertencentes ao
     * tenant do teste.
     *
     * @param dataSource fonte de conexões JDBC para a operação
     * @param functionName nome do objeto criado no schema isolado
     * @param triggerName nome do objeto criado no schema isolado
     * @param tenantId identificador do tenant que delimita a operação
     * @throws SQLException se a operação JDBC falhar
     */
    private void createFailureTrigger(
            DataSource dataSource,
            String functionName,
            String triggerName,
            UUID tenantId
    ) throws SQLException {

        String createFunction = """
            CREATE FUNCTION %s()
            RETURNS trigger AS $$
            BEGIN
                IF NEW.tenant_id = '%s'::uuid THEN
                    RAISE EXCEPTION
                        'SIMULATED_OUTBOX_FAILURE';
                END IF;

                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """.formatted(
                functionName,
                tenantId
        );

        String createTrigger = """
            CREATE TRIGGER %s
            BEFORE INSERT ON outbox_events
            FOR EACH ROW
            EXECUTE FUNCTION %s()
            """.formatted(
                triggerName,
                functionName
        );

        try (Connection connection
                = dataSource.getConnection()) {

            try (var statement
                    = connection.createStatement()) {

                statement.execute(createFunction);
                statement.execute(createTrigger);
            }
        }
    }

    /**
     * Remove a trigger e a função após o teste.
     *
     * @param dataSource fonte de conexões JDBC para a operação
     * @param functionName nome do objeto criado no schema isolado
     * @param triggerName nome do objeto criado no schema isolado
     * @throws SQLException se a operação JDBC falhar
     */
    private void dropFailureTrigger(
            DataSource dataSource,
            String functionName,
            String triggerName
    ) throws SQLException {

        try (
                Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(
                    "DROP TRIGGER IF EXISTS "
                    + triggerName
                    + " ON outbox_events"
            );

            statement.execute(
                    "DROP FUNCTION IF EXISTS "
                    + functionName
                    + "()"
            );
        }
    }

    /**
     * Conta registros relacionados ao teste.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param sql instrução SQL executada na conexão de teste
     * @param parameter valores vinculados aos parâmetros SQL
     * @return quantidade de registros no escopo da consulta
     * @throws SQLException se a operação JDBC falhar
     */
    private long count(
            Connection connection,
            String sql,
            UUID parameter
    ) throws SQLException {

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setObject(1, parameter);

            try (ResultSet result
                    = statement.executeQuery()) {

                if (!result.next()) {
                    throw new SQLException(
                            "Count query returned no rows"
                    );
                }

                return result.getLong(1);
            }
        }
    }
}
