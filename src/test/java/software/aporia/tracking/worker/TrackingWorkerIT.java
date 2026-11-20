package software.aporia.tracking.worker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.storage.Database;

class TrackingWorkerIT {
  @Test
    void shouldRejectPacketWithDifferentDeviceIdentity()
            throws Exception {

        UUID tenantId = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

        UUID deviceId = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

        UUID packetId = UUID.randomUUID();

        byte[] payload = (
            "ALV;9999999999\r"
        ).getBytes(StandardCharsets.US_ASCII);

        String hash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(payload)
        );

        try (var dataSource = Database.createDataSource()) {

            try (Connection connection =
                    dataSource.getConnection()) {

                String insertSql = """
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

                try (PreparedStatement statement =
                        connection.prepareStatement(insertSql)) {

                    statement.setObject(1, packetId);
                    statement.setObject(2, tenantId);
                    statement.setObject(3, deviceId);
                    statement.setBytes(4, payload);
                    statement.setString(5, hash);
                    statement.setObject(6, UUID.randomUUID());

                    statement.executeUpdate();
                }
            }

            TrackingWorker worker = new TrackingWorker(dataSource);

            assertTrue(worker.processOne());

            try (Connection connection =
                    dataSource.getConnection()) {

                String sql = """
                    SELECT status, error_code, attempts
                    FROM raw_packets
                    WHERE id = ?
                    """;

                try (PreparedStatement statement =
                        connection.prepareStatement(sql)) {

                    statement.setObject(1, packetId);

                    try (ResultSet result =
                            statement.executeQuery()) {

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