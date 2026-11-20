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

import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

class TrackingWorkerIT {

    @Test
    void shouldRejectPacketWithDifferentDeviceIdentity()
            throws Exception {

        try (
                //cria a conexão com o banco de testes
                var dataSource = TestDatabase.createDataSource(); //cria os recursos para testar
                 var fixtures = new TestFixtures(
                        dataSource,
                        "1234567890"
                )) {
            //cria um uuid aleatorio para id 
            UUID packetId = UUID.randomUUID();

            //payload mock
            byte[] payload = ("ALV;9999999999\r").getBytes(StandardCharsets.US_ASCII);

            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(payload)
            );

            // Inserir o pacote no banco de testes.
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

            // Executar o worker.
            TrackingWorker worker = new TrackingWorker(
                    dataSource,
                    fixtures.tenantId()
            );

            assertTrue(worker.processOne());

            // Verificar a rejeição.
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
