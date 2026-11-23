package software.aporia.tracking.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DeviceCommandRepository {

    public UUID enqueue(
            Connection connection,
            UUID tenantId,
            UUID deviceId,
            String protocol,
            byte[] payload,
            Instant expiresAt
    ) throws SQLException {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(protocol);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(expiresAt);

        if (protocol.isBlank()) {
            throw new IllegalArgumentException("INVALID_PROTOCOL");
        }

        if (payload.length == 0 || payload.length > 8192) {
            throw new IllegalArgumentException("INVALID_COMMAND_PAYLOAD");
        }

        if (!expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("INVALID_EXPIRATION");
        }

        UUID commandId = UUID.randomUUID();

        String sql = """
            INSERT INTO device_commands (
                id,
                tenant_id,
                device_id,
                protocol,
                payload,
                status,
                expires_at
            )
            SELECT
                ?, d.tenant_id, d.id, d.protocol,
                ?, 'queued', ?
            FROM devices d
            WHERE d.tenant_id = ?
              AND d.id = ?
              AND d.protocol = ?
              AND d.enabled = true
            """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setObject(1, commandId);
            statement.setBytes(2, payload);
            statement.setObject(
                    3,
                    expiresAt.atOffset(java.time.ZoneOffset.UTC)
            );
            statement.setObject(4, tenantId);
            statement.setObject(5, deviceId);
            statement.setString(6, protocol);

            int inserted = statement.executeUpdate();

            if (inserted != 1) {
                throw new IllegalArgumentException(
                        "UNKNOWN_OR_DISABLED_DEVICE"
                );
            }
        }

        return commandId;
    }

    public Optional<String> findStatus(
            Connection connection,
            UUID tenantId,
            UUID commandId
    ) throws SQLException {

        Objects.requireNonNull(connection);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(commandId);

        String sql = """
        SELECT status
        FROM device_commands
        WHERE tenant_id = ?
          AND id = ?
        """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setObject(1, tenantId);
            statement.setObject(2, commandId);

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    return Optional.empty();
                }

                return Optional.of(
                        result.getString("status")
                );
            }
        }
    }

}
