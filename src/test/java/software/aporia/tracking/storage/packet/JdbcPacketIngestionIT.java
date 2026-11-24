package software.aporia.tracking.storage.packet;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import software.aporia.tracking.core.ReceivedFrame;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifica a ingestão para um equipamento cadastrado e o tratamento de identidades desconhecidas. */
class JdbcPacketIngestionIT {

    /**
     * Confere a ingestão de um quadro de dispositivo cadastrado.
     *
     * @throws SQLException se a operação JDBC falhar
     */
    @Test
    void shouldAcceptRegisteredDevice() throws SQLException {
        try (var dataSource = TestDatabase.createDataSource();
             var fixtures = new TestFixtures(dataSource, "9876500061")) {
            String identity = fixtures.deviceIdentity();
            ReceivedFrame frame = new ReceivedFrame(
                    "suntech",
                    identity,
                    ("ALV;" + identity + "\r").getBytes(StandardCharsets.US_ASCII), "suntech-ascii-v3"
            );

            UUID packetId = new JdbcPacketIngestion(dataSource).accept(frame, fixtures.tenantId(), fixtures.deviceId());

            assertNotNull(packetId);
            try (var connection = dataSource.getConnection(); var query = connection.prepareStatement("""
                    SELECT payload, status, device_id FROM raw_packets WHERE tenant_id=? AND id=?
                    """)) {
                query.setObject(1, fixtures.tenantId());
                query.setObject(2, packetId);
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertArrayEquals(frame.payload(), row.getBytes("payload"));
                    assertEquals("pending", row.getString("status"));
                    assertEquals(fixtures.deviceId(), row.getObject("device_id", UUID.class));
                }
            }
        }
    }

    /** Verifica a rejeição de um quadro cujo dispositivo não está habilitado no cadastro. */
    @Test
    void shouldRejectUnknownDevice() {

        ReceivedFrame frame = new ReceivedFrame(
                "suntech",
                "9999999999",
                "ALV;9999999999\r".getBytes(
                        StandardCharsets.US_ASCII
                ), "suntech-ascii-v3"
        );

        try (var dataSource = TestDatabase.createDataSource()) {
            var ingestion = new JdbcPacketIngestion(dataSource);
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> ingestion.accept(frame, UUID.randomUUID(), UUID.randomUUID())
            );

            assertEquals(
                    "Unknown or disabled device",
                    exception.getMessage()
            );
        }
    }

    /** A identidade correta não permite ignorar o tenant ou o dispositivo da sessão. */
    @Test
    void shouldRequireMatchingSessionScope() throws Exception {
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db)) {
            var ingestion = new JdbcPacketIngestion(db);
            var frame = new ReceivedFrame("suntech", fixture.deviceIdentity(),
                    ("ALV;" + fixture.deviceIdentity() + "\r").getBytes(StandardCharsets.US_ASCII), "suntech-ascii-v3");
            assertThrows(NullPointerException.class, () -> ingestion.accept(frame, null, fixture.deviceId()));
            assertThrows(NullPointerException.class, () -> ingestion.accept(frame, fixture.tenantId(), null));
            assertThrows(IllegalArgumentException.class, () -> ingestion.accept(frame, UUID.randomUUID(), fixture.deviceId()));
            assertThrows(IllegalArgumentException.class, () -> ingestion.accept(frame, fixture.tenantId(), UUID.randomUUID()));
            try (var connection = db.getConnection(); var query = connection.prepareStatement(
                    "SELECT count(*) FROM raw_packets WHERE tenant_id=?")) {
                query.setObject(1, fixture.tenantId());
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals(0, row.getInt(1));
                }
            }
        }
    }

}
