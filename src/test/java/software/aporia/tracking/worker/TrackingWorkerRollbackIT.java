package software.aporia.tracking.worker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import software.aporia.tracking.observability.metrics.WorkerMetrics;
import software.aporia.tracking.protocol.DecoderRegistry;
import software.aporia.tracking.protocol.suntech.SuntechDecoder;
import software.aporia.tracking.protocol.teltonika.TeltonikaAvl;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

/** Verifica que a ausência de associação ao ativo desfaz a mensagem parcial antes do reagendamento. */
class TrackingWorkerRollbackIT {

    /** Registro isolado para observar os contadores de cada cenário. */
    private SimpleMeterRegistry meterRegistry;
    /** Métricas do worker ligadas ao registro acima. */
    private WorkerMetrics metrics;
    /** Decodificadores Suntech e Teltonika disponíveis ao worker. */
    private DecoderRegistry decoderRegistry;

    /** Prepara métricas isoladas e os dois decodificadores usados pelo worker. */
    @BeforeEach
    void setUpWorkerDependencies() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new WorkerMetrics(meterRegistry);
        decoderRegistry = new DecoderRegistry(List.of(
                new SuntechDecoder(), new TeltonikaAvl()));
    }

    /** Fecha o registro de métricas para não vazar estado entre testes. */
    @AfterEach
    void closeMeterRegistry() {
        meterRegistry.close();
    }

    /**
     * Usa uma leitura fora da vigência da associação e verifica o rollback antes da nova
     * tentativa.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRollbackMessageWhenAssignmentIsMissing()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource,
                "1234567890"
        )) {

            UUID packetId = UUID.randomUUID();

            // Associação começa em 2026-01-01.
            // O pacote possui data anterior.
            byte[] payload = ("STT;1234567890;013831;20251220;08:45:00;"
                    + "-16.6700;-49.2500;45.5;1\r").getBytes(StandardCharsets.US_ASCII);

            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(payload)
            );

            // Inserir pacote no banco exclusivo de testes.
            try (Connection connection
                    = dataSource.getConnection()) {

                String sql = """
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
                    """;

                try (PreparedStatement statement
                        = connection.prepareStatement(sql)) {

                    statement.setObject(1, packetId);
                    statement.setObject(2, fixtures.tenantId());
                    statement.setObject(3, fixtures.deviceId());
                    statement.setBytes(4, payload);
                    statement.setString(5, hash);
                    statement.setObject(6, UUID.randomUUID());

                    statement.executeUpdate();
                }
            }

            TrackingWorker worker = new TrackingWorker(
                    dataSource,
                    fixtures.tenantId(), decoderRegistry, metrics);

            // O worker desfaz a mensagem e agenda uma nova tentativa.
            assertTrue(worker.processOne());

            // Verificar que nenhuma gravação parcial foi confirmada.
            try (Connection connection
                    = dataSource.getConnection()) {

                String packetSql = """
                    SELECT
                        status,
                        attempts,
                        error_code,
                        available_at,
                        received_at
                    FROM raw_packets
                    WHERE id = ?
                    """;

                try (PreparedStatement statement
                        = connection.prepareStatement(packetSql)) {

                    statement.setObject(1, packetId);

                    try (ResultSet result
                            = statement.executeQuery()) {

                        assertTrue(result.next());

                        // O pacote permanece pendente.
                        assertEquals(
                                "pending",
                                result.getString("status")
                        );

                        // A primeira tentativa foi registrada.
                        assertEquals(
                                1,
                                result.getInt("attempts")
                        );

                        // O motivo da falha foi registrado.
                        assertEquals(
                                "ASSIGNMENT_NOT_FOUND",
                                result.getString("error_code")
                        );

                        // A próxima tentativa foi agendada.
                        assertNotNull(
                                result.getTimestamp("available_at")
                        );

                        // O agendamento deve ser posterior ao recebimento.
                        assertTrue(
                                result.getTimestamp("available_at")
                                        .toInstant()
                                        .isAfter(
                                                result.getTimestamp("received_at")
                                                        .toInstant()
                                        )
                        );

                    }
                }

                String messageSql = """
                    SELECT COUNT(*)
                    FROM messages
                    WHERE raw_packet_id = ?
                    """;

                try (PreparedStatement statement
                        = connection.prepareStatement(messageSql)) {

                    statement.setObject(1, packetId);

                    try (ResultSet result
                            = statement.executeQuery()) {

                        assertTrue(result.next());
                        assertEquals(0, result.getInt(1));
                    }
                }
            }
        }
    }
}
