package software.aporia.tracking.storage.command;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.support.TestDatabase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifica persistência transacional, duplicação e escopo das referências usadas nas respostas. */
class DeviceCommandResponseRepositoryIT {

    private static HikariDataSource dataSource() {

        return TestDatabase.createDataSource();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private void createFixture(
            Connection connection,
            UUID tenantId,
            UUID deviceId,
            UUID packetId,
            String identity
    ) throws Exception {

        try (PreparedStatement statement
                = connection.prepareStatement(
                        "INSERT INTO tenants (id, name) VALUES (?, ?)"
                )) {

                    statement.setObject(1, tenantId);
                    statement.setString(2, "Command Response IT");
                    statement.executeUpdate();
                }

                try (PreparedStatement statement
                        = connection.prepareStatement(
                                """
                        INSERT INTO devices
                            (id, tenant_id, protocol, identity, enabled)
                        VALUES (?, ?, 'suntech', ?, true)
                        """
                        )) {

                            statement.setObject(1, deviceId);
                            statement.setObject(2, tenantId);
                            statement.setString(3, identity);
                            statement.executeUpdate();
                        }

                        byte[] payload = bytes(
                                "RES;" + identity + ";03;04;01;STB.201.1.1.11\r"
                        );

                        String hash = java.util.HexFormat.of().formatHex(
                                java.security.MessageDigest
                                        .getInstance("SHA-256")
                                        .digest(payload)
                        );

                        try (PreparedStatement statement
                                = connection.prepareStatement(
                                        """
                        INSERT INTO raw_packets (
                            id, tenant_id, device_id,
                            payload, payload_hash,
                            decoder_version, status,
                            correlation_id
                        )
                        VALUES (?, ?, ?, ?, ?, ?, 'pending', ?)
                        """
                                )) {

                                    statement.setObject(1, packetId);
                                    statement.setObject(2, tenantId);
                                    statement.setObject(3, deviceId);
                                    statement.setBytes(4, payload);
                                    statement.setString(5, hash);
                                    statement.setString(6, "suntech-ascii-v3");
                                    statement.setObject(7, UUID.randomUUID());

                                    statement.executeUpdate();
                                }
    }

    @Test
    void shouldPersistResponseAndRollback() throws Exception {

        try (HikariDataSource ds = dataSource(); Connection connection = ds.getConnection()) {

            connection.setAutoCommit(false);

            UUID tenantId = UUID.randomUUID();
            UUID deviceId = UUID.randomUUID();
            UUID packetId = UUID.randomUUID();

            // Identidade exclusiva para evitar conflitos.
            String identity = String.format(
                    "%010d",
                    Math.floorMod(
                            UUID.randomUUID().getLeastSignificantBits(),
                            10_000_000_000L
                    )
            );

            try {
                createFixture(
                        connection,
                        tenantId,
                        deviceId,
                        packetId,
                        identity
                );

                DeviceCommandResponseRepository repository
                        = new DeviceCommandResponseRepository();

                byte[] response = bytes(
                        "RES;" + identity
                        + ";03;04;01;STB.201.1.1.11\r"
                );

                UUID responseId = repository.save(
                        connection,
                        tenantId,
                        deviceId,
                        packetId,
                        "suntech",
                        "RES",
                        response
                );

                assertNotNull(responseId);

                try (PreparedStatement statement
                        = connection.prepareStatement(
                                """
                                SELECT command_id, response_type,
                                       result, payload
                                FROM device_command_responses
                                WHERE id = ?
                                """
                        )) {

                            statement.setObject(1, responseId);

                            try (ResultSet result = statement.executeQuery()) {

                                assertTrue(result.next());

                                assertNull(result.getObject("command_id"));
                                assertEquals(
                                        "RES",
                                        result.getString("response_type")
                                );
                                assertEquals(
                                        "unknown",
                                        result.getString("result")
                                );
                                assertArrayEquals(
                                        response,
                                        result.getBytes("payload")
                                );
                            }
                        }

                        // Mesmo pacote não pode gerar outra resposta.
                        UUID duplicate = repository.save(
                                connection,
                                tenantId,
                                deviceId,
                                packetId,
                                "suntech",
                                "RES",
                                response
                        );

                        assertNull(duplicate);

            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void shouldRejectInvalidResponseType() throws Exception {

        try (HikariDataSource ds = dataSource(); Connection connection = ds.getConnection()) {

            DeviceCommandResponseRepository repository
                    = new DeviceCommandResponseRepository();

            var error = assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.save(
                            connection,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "suntech",
                            "STT",
                            bytes("STT;1234567890\r")
                    )
            );
            assertEquals("INVALID_RESPONSE_TYPE", error.getMessage());
        }
    }

    @Test
    void shouldRejectCrossTenantPacketReference() throws Exception {

        try (HikariDataSource ds = dataSource(); Connection connection = ds.getConnection()) {

            connection.setAutoCommit(false);

            UUID tenantId = UUID.randomUUID();
            UUID deviceId = UUID.randomUUID();
            UUID packetId = UUID.randomUUID();

            String identity = String.format(
                    "%010d",
                    Math.floorMod(
                            UUID.randomUUID().getMostSignificantBits(),
                            10_000_000_000L
                    )
            );

            try {
                createFixture(
                        connection,
                        tenantId,
                        deviceId,
                        packetId,
                        identity
                );

                DeviceCommandResponseRepository repository
                        = new DeviceCommandResponseRepository();

                var error = assertThrows(
                        java.sql.SQLException.class,
                        () -> repository.save(
                                connection,
                                UUID.randomUUID(),
                                deviceId,
                                packetId,
                                "suntech",
                                "RES",
                                bytes("RES;" + identity + ";03;04;1\r")
                        )
                );
                assertEquals("23503", error.getSQLState());

            } finally {
                connection.rollback();
            }
        }
    }
}
