package software.aporia.tracking.worker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

/** Verifica a persistência de respostas Suntech e o tratamento de telemetria presente na mesma mensagem. */
class TrackingWorkerMessageRoutingIT {

    private static final String IDENTITY = "1234567890";

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private UUID insertPacket(
            Connection connection,
            TestFixtures fixtures,
            byte[] payload
    ) throws Exception {

        UUID packetId = UUID.randomUUID();

        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(payload)
        );

        try (PreparedStatement statement
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

        return packetId;
    }

    private void assertPacketProcessed(
            Connection connection,
            UUID packetId
    ) throws Exception {

        try (PreparedStatement statement
                = connection.prepareStatement("""
                    SELECT status
                    FROM raw_packets
                    WHERE id = ?
                    """)) {

            statement.setObject(1, packetId);

            try (ResultSet result = statement.executeQuery()) {

                assertTrue(result.next());
                assertEquals(
                        "processed",
                        result.getString("status")
                );
            }
        }
    }

    private void assertResponse(
            Connection connection,
            UUID packetId,
            String expectedType,
            byte[] expectedPayload
    ) throws Exception {

        try (PreparedStatement statement
                = connection.prepareStatement("""
                    SELECT
                        response_type,
                        result,
                        command_id,
                        payload
                    FROM device_command_responses
                    WHERE raw_packet_id = ?
                    """)) {

            statement.setObject(1, packetId);

            try (ResultSet result = statement.executeQuery()) {

                assertTrue(result.next());

                assertEquals(
                        expectedType,
                        result.getString("response_type")
                );

                assertEquals(
                        "unknown",
                        result.getString("result")
                );

                assertNull(
                        result.getObject("command_id")
                );

                assertArrayEquals(
                        expectedPayload,
                        result.getBytes("payload")
                );

                assertFalse(result.next());
            }
        }
    }

    private int countPositions(
            Connection connection,
            UUID packetId
    ) throws Exception {

        try (PreparedStatement statement
                = connection.prepareStatement("""
                    SELECT COUNT(*)
                    FROM positions p
                    JOIN messages m
                      ON m.id = p.message_id
                    WHERE m.raw_packet_id = ?
                    """)) {

            statement.setObject(1, packetId);

            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    @Test
    void shouldProcessConventionalCommandResponse()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, IDENTITY
        )) {

            byte[] payload = bytes(
                    "RES;1234567890;03;04;01;"
                    + "STB.201.1.1.11\r"
            );

            UUID packetId;

            try (Connection connection
                    = dataSource.getConnection()) {

                packetId = insertPacket(
                        connection, fixtures, payload
                );
            }

            TrackingWorker worker = new TrackingWorker(
                    dataSource, fixtures.tenantId()
            );

            assertTrue(worker.processOne());

            try (Connection connection
                    = dataSource.getConnection()) {

                assertPacketProcessed(connection, packetId);

                assertResponse(
                        connection,
                        packetId,
                        "RES",
                        payload
                );

                assertEquals(
                        0,
                        countPositions(connection, packetId)
                );
            }
        }
    }

    @Test
    void shouldProcessRprResponse()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, IDENTITY
        )) {

            byte[] payload = bytes(
                    "RPR;1234567890;OK;10;13#0\r"
            );

            UUID packetId;

            try (Connection connection
                    = dataSource.getConnection()) {

                packetId = insertPacket(
                        connection, fixtures, payload
                );
            }

            TrackingWorker worker = new TrackingWorker(
                    dataSource, fixtures.tenantId()
            );

            assertTrue(worker.processOne());

            try (Connection connection
                    = dataSource.getConnection()) {

                assertPacketProcessed(connection, packetId);

                assertResponse(
                        connection,
                        packetId,
                        "RPR",
                        payload
                );

                assertEquals(
                        0,
                        countPositions(connection, packetId)
                );
            }
        }
    }

    @Test
    void shouldProcessTelemetryInsideCommandResponse()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, IDENTITY
        )) {

            byte[] payload = bytes(
                    "RES;STT;1234567890;013831;"
                    + "20261120;08:45:00;"
                    + "-16.6700;-49.2500;45.5;1\r"
            );

            UUID packetId;

            try (Connection connection
                    = dataSource.getConnection()) {

                packetId = insertPacket(
                        connection, fixtures, payload
                );
            }

            TrackingWorker worker = new TrackingWorker(
                    dataSource, fixtures.tenantId()
            );

            assertTrue(worker.processOne());

            try (Connection connection
                    = dataSource.getConnection()) {

                assertPacketProcessed(connection, packetId);

                assertResponse(
                        connection,
                        packetId,
                        "RES",
                        payload
                );

                assertEquals(
                        1,
                        countPositions(connection, packetId)
                );

                try (PreparedStatement statement
                        = connection.prepareStatement("""
                            SELECT
                                ST_Y(p.geom) AS latitude,
                                ST_X(p.geom) AS longitude,
                                p.speed_kph
                            FROM positions p
                            JOIN messages m
                              ON m.id = p.message_id
                            WHERE m.raw_packet_id = ?
                            """)) {

                    statement.setObject(1, packetId);

                    try (ResultSet result
                            = statement.executeQuery()) {

                        assertTrue(result.next());

                        assertEquals(
                                -16.67,
                                result.getDouble("latitude"),
                                0.000001
                        );

                        assertEquals(
                                -49.25,
                                result.getDouble("longitude"),
                                0.000001
                        );

                        assertEquals(
                                45.5,
                                result.getDouble("speed_kph"),
                                0.000001
                        );
                    }
                }
            }
        }
    }
}
