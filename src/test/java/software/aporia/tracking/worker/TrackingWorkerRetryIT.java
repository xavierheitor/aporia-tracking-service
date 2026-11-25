package software.aporia.tracking.worker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import software.aporia.tracking.observability.metrics.WorkerMetrics;
import software.aporia.tracking.protocol.DecoderRegistry;
import software.aporia.tracking.protocol.suntech.SuntechDecoder;
import software.aporia.tracking.protocol.teltonika.TeltonikaAvl;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

/** Acompanha um pacote pelas tentativas de processamento até sua rejeição definitiva. */
class TrackingWorkerRetryIT {

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
     * Antecipa o próximo processamento em cada ciclo até atingir a quinta tentativa e a rejeição.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRetryAndRejectAfterFiveAttempts() throws Exception {
        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(dataSource, "1234567890");) {
            UUID packetId = UUID.randomUUID();

            // Data anterior ao inicio da associação
            // Data anterior ao início da associação.
            byte[] payload = ("STT;1234567890;013831;20251220;08:45:00;"
                    + "-16.6700;-49.2500;45.5;1\r").getBytes(StandardCharsets.US_ASCII);

            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(payload)
            );
            // Criar o pacote.
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement
                    = connection.prepareStatement("""
                         INSERT INTO raw_packets (
                             id, tenant_id, device_id,
                             payload, payload_hash,
                             decoder_version, correlation_id
                         )
                         VALUES (?, ?, ?, ?, ?,
                                 'suntech-ascii-v3', ?)
                         """)) {

                statement.setObject(1, packetId);
                statement.setObject(2, fixtures.tenantId());
                statement.setObject(3, fixtures.deviceId());
                statement.setBytes(4, payload);
                statement.setString(5, hash);
                statement.setObject(6, UUID.randomUUID());
                statement.executeUpdate();
            }

            TrackingWorker worker = new TrackingWorker(
                    dataSource,
                    fixtures.tenantId(), decoderRegistry, metrics);

            long[] expectedDelays = {10, 30, 60, 120};

            for (int attempt = 1; attempt <= 5; attempt++) {

                Instant before = Instant.now();

                assertTrue(worker.processOne());

                try (Connection connection
                        = dataSource.getConnection()) {

                    String sql = """
                        SELECT status, attempts,
                               error_code, available_at
                        FROM raw_packets
                        WHERE id = ?
                        """;

                    try (PreparedStatement statement
                            = connection.prepareStatement(sql)) {

                        statement.setObject(1, packetId);

                        try (ResultSet result
                                = statement.executeQuery()) {

                            assertTrue(result.next());

                            assertEquals(
                                    attempt,
                                    result.getInt("attempts")
                            );

                            assertEquals(
                                    "ASSIGNMENT_NOT_FOUND",
                                    result.getString("error_code")
                            );

                            if (attempt < 5) {

                                assertEquals(
                                        "pending",
                                        result.getString("status")
                                );

                                Instant availableAt
                                        = result.getTimestamp("available_at")
                                                .toInstant();

                                long actualDelay
                                        = Duration.between(
                                                before,
                                                availableAt
                                        ).toSeconds();

                                assertTrue(
                                        Math.abs(
                                                actualDelay
                                                - expectedDelays[attempt - 1]
                                        ) <= 2,
                                        "Unexpected retry delay: "
                                        + actualDelay
                                );

                            } else {

                                assertEquals(
                                        "rejected",
                                        result.getString("status")
                                );
                            }
                        }
                    }

                    // Antecipar o agendamento para que
                    // o próximo ciclo não precise esperar.
                    if (attempt < 5) {
                        try (PreparedStatement statement
                                = connection.prepareStatement("""
                                    UPDATE raw_packets
                                    SET available_at =
                                        clock_timestamp()
                                        - INTERVAL '1 second'
                                    WHERE id = ?
                                    """)) {

                            statement.setObject(1, packetId);
                            statement.executeUpdate();
                        }
                    }
                }
            }

            // Depois da rejeição, o pacote não deve
            // ser selecionado novamente.
            assertFalse(worker.processOne());
            assertEquals(4.0, meterRegistry.get("tracking.packets.retry.scheduled").counter().count());
            assertEquals(1.0, meterRegistry.get("tracking.packets.rejected").counter().count());
            assertEquals(1.0, meterRegistry.get("tracking.worker.cycles.empty").counter().count());
            assertEquals(0.0, meterRegistry.get("tracking.worker.cycles.failed").counter().count());
            assertEquals(5L, meterRegistry.get("tracking.packet.processing").timer().count());
        }
    }
}
