package software.aporia.tracking.worker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

/** Verifica a consistência entre histórico de posições, última posição e evento na outbox. */
class TrackingWorkerPositionIT {

    /**
     * Confere histórico, última posição e evento produzidos pela mesma leitura.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldPersistPositionAndOutboxAtomically()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "1234567890"
        )) {
            UUID packetId = UUID.randomUUID();

            byte[] payload = ("STT;1234567890;013831;20261120;08:45:00;"
                    + "-16.6700;-49.2500;45.5;1\r").getBytes(StandardCharsets.US_ASCII);

            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(payload)
            );

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
                    dataSource, fixtures.tenantId()
            );

            assertTrue(worker.processOne());

            try (Connection connection = dataSource.getConnection()) {

                // Confirmar que o pacote foi processado.
                try (PreparedStatement statement
                        = connection.prepareStatement("""
                         SELECT status, attempts
                         FROM raw_packets
                         WHERE id = ?
                         """)) {

                    statement.setObject(1, packetId);

                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals("processed", result.getString("status"));
                        assertEquals(1, result.getInt("attempts"));
                    }
                }

                // Confirmar coordenadas, veículo e velocidade.
                try (PreparedStatement statement
                        = connection.prepareStatement("""
                         SELECT
                             p.id,
                             p.asset_id,
                             ST_Y(p.geom) AS latitude,
                             ST_X(p.geom) AS longitude,
                             p.speed_kph
                         FROM positions p
                         JOIN messages m
                           ON m.id = p.message_id
                         WHERE m.raw_packet_id = ?
                         """)) {

                    statement.setObject(1, packetId);

                    UUID positionId;

                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next());

                        positionId = result.getObject("id", UUID.class);

                        assertEquals(
                                fixtures.assetId(),
                                result.getObject("asset_id", UUID.class)
                        );

                        assertEquals(-16.67, result.getDouble("latitude"), 0.000001);
                        assertEquals(-49.25, result.getDouble("longitude"), 0.000001);
                        assertEquals(45.5, result.getDouble("speed_kph"), 0.000001);

                        assertFalse(result.next());
                    }

                    // A última posição deve apontar para a posição criada.
                    try (PreparedStatement lastStatement
                            = connection.prepareStatement("""
                             SELECT position_id
                             FROM last_positions
                             WHERE tenant_id = ? AND asset_id = ?
                             """)) {

                        lastStatement.setObject(1, fixtures.tenantId());
                        lastStatement.setObject(2, fixtures.assetId());

                        try (ResultSet result = lastStatement.executeQuery()) {
                            assertTrue(result.next());
                            assertEquals(
                                    positionId,
                                    result.getObject("position_id", UUID.class)
                            );
                        }
                    }

                    // Deve existir exatamente um evento na outbox.
                    try (PreparedStatement eventStatement
                            = connection.prepareStatement("""
                             SELECT COUNT(*)
                             FROM outbox_events
                             WHERE tenant_id = ?
                               AND event_type = 'tracking.position.created'
                               AND aggregate_id = ?
                             """)) {

                        eventStatement.setObject(1, fixtures.tenantId());
                        eventStatement.setObject(2, fixtures.assetId());

                        try (ResultSet result = eventStatement.executeQuery()) {
                            assertTrue(result.next());
                            assertEquals(1, result.getInt(1));
                        }
                    }
                }
            }
        }
    }
}
