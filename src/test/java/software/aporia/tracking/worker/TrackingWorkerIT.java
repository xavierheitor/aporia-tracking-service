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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import software.aporia.tracking.observability.metrics.WorkerMetrics;
import software.aporia.tracking.protocol.DecoderRegistry;
import software.aporia.tracking.protocol.suntech.SuntechDecoder;
import software.aporia.tracking.protocol.teltonika.TeltonikaAvl;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

/** Verifica a rejeição de um pacote cuja identidade diverge da identidade cadastrada. */
class TrackingWorkerIT {

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
     * Rejeita o pacote cuja identidade decodificada diverge da identidade cadastrada.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectPacketWithDifferentDeviceIdentity()
            throws Exception {

        try (
                // DataSource isolado do banco de testes e fixture do dispositivo cadastrado.
                var dataSource = TestDatabase.createDataSource();
                var fixtures = new TestFixtures(
                        dataSource,
                        "1234567890"
                )) {
            UUID packetId = UUID.randomUUID();

            // ALV é uma mensagem Suntech sem posição, mas declara outra identidade
            // (9999999999) diferente da cadastrada (1234567890).
            byte[] payload = ("ALV;9999999999\r").getBytes(StandardCharsets.US_ASCII);

            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(payload)
            );

            // Insere o pacote diretamente na fila, como o gateway faria após a recepção.
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
                    VALUES (?, ?, ?, ?, ?,
                            'suntech-ascii-v3', ?)
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

            // O worker deve consumir o pacote e comparar a identidade decodificada.
            TrackingWorker worker = new TrackingWorker(
                    dataSource,
                    fixtures.tenantId(), decoderRegistry, metrics);

            assertTrue(worker.processOne());
            assertEquals(1.0, meterRegistry.get("tracking.packets.rejected").counter().count());
            assertEquals(0.0, meterRegistry.get("tracking.packets.processed").counter().count());
            assertEquals(1L, meterRegistry.get("tracking.packet.processing").timer().count());

            // Confere o estado final: rejeitado, com uma tentativa contabilizada.
            try (Connection connection
                    = dataSource.getConnection()) {

                String sql = """
                    SELECT status, error_code, attempts
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
                                "rejected",
                                result.getString("status")
                        );

                        assertEquals(
                                "DEVICE_IDENTITY_MISMATCH",
                                result.getString("error_code")
                        );

                        assertEquals(
                                1,
                                result.getInt("attempts")
                        );
                    }
                }
            }
        }
    }
}
