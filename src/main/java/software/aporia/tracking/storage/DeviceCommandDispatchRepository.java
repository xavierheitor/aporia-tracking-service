package software.aporia.tracking.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DeviceCommandDispatchRepository {

    public record ClaimedCommand(
            UUID id,
            UUID tenantId,
            UUID deviceId,
            String protocol,
            byte[] payload,
            int attempts,
            UUID claimToken
            ) {

        public ClaimedCommand {
            Objects.requireNonNull(id);
            Objects.requireNonNull(tenantId);
            Objects.requireNonNull(deviceId);
            Objects.requireNonNull(protocol);
            Objects.requireNonNull(payload);
            Objects.requireNonNull(claimToken);

            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public String toString() {
            return "ClaimedCommand[id=" + id
                    + ", tenantId=" + tenantId
                    + ", deviceId=" + deviceId
                    + ", protocol=" + protocol
                    + ", payloadBytes=" + payload.length
                    + ", attempts=" + attempts
                    + ", claimToken=" + claimToken + "]";
        }
    }

    public Optional<ClaimedCommand> claimNext(
            Connection connection
    ) throws SQLException {

        Objects.requireNonNull(connection);

        if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                    "CLAIM_REQUIRES_TRANSACTION"
            );
        }

        UUID token = UUID.randomUUID();

        String sql = """
        WITH candidate AS (
            SELECT c.id
            FROM device_commands c
            JOIN devices d
              ON d.tenant_id = c.tenant_id
             AND d.id = c.device_id
            WHERE c.status = 'queued'
              AND c.available_at <= NOW()
              AND c.expires_at > NOW()
              AND d.enabled = true
              AND d.protocol = c.protocol
            ORDER BY c.available_at, c.created_at, c.id
            FOR UPDATE OF c SKIP LOCKED
            LIMIT 1
        )
        UPDATE device_commands c
        SET status = 'sending',
            attempts = c.attempts + 1,
            claimed_at = NOW(),
            claim_token = ?
        FROM candidate
        WHERE c.id = candidate.id
          AND c.status = 'queued'
        RETURNING
            c.id,
            c.tenant_id,
            c.device_id,
            c.protocol,
            c.payload,
            c.attempts,
            c.claim_token
        """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            // Associa o UUID ao parâmetro do SQL.
            statement.setObject(1, token);

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    return Optional.empty();
                }

                ClaimedCommand command = new ClaimedCommand(
                        result.getObject("id", UUID.class),
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("device_id", UUID.class),
                        result.getString("protocol"),
                        result.getBytes("payload"),
                        result.getInt("attempts"),
                        result.getObject("claim_token", UUID.class)
                );

                if (result.next()) {
                    throw new SQLException(
                            "MULTIPLE_COMMANDS_CLAIMED"
                    );
                }

                return Optional.of(command);
            }
        }
    }

    public boolean markSent(
            Connection connection,
            UUID tenantId,
            UUID commandId,
            UUID claimToken
    ) throws SQLException {

        Objects.requireNonNull(connection);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(commandId);
        Objects.requireNonNull(claimToken);

        String sql = """
        UPDATE device_commands
        SET status = 'sent',
            sent_at = NOW()
        WHERE tenant_id = ?
          AND id = ?
          AND claim_token = ?
          AND status = 'transmitting'
        """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setObject(1, tenantId);
            statement.setObject(2, commandId);
            statement.setObject(3, claimToken);

            return statement.executeUpdate() == 1;
        }
    }

    public boolean authorizeTransmission(
            Connection connection,
            UUID tenantId,
            UUID commandId,
            UUID claimToken
    ) throws SQLException {

        Objects.requireNonNull(connection);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(commandId);
        Objects.requireNonNull(claimToken);

        String sql = """
        UPDATE device_commands
        SET status = 'transmitting',
            transmission_started_at = NOW()
        WHERE tenant_id = ?
          AND id = ?
          AND claim_token = ?
          AND status = 'sending'
          AND expires_at > NOW()
        """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setObject(1, tenantId);
            statement.setObject(2, commandId);
            statement.setObject(3, claimToken);

            return statement.executeUpdate() == 1;
        }
    }

    public Optional<ClaimedCommand> claimNextForDevice(
            Connection connection,
            UUID tenantId,
            UUID deviceId
    ) throws SQLException {

        Objects.requireNonNull(connection);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);

        if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                    "CLAIM_REQUIRES_TRANSACTION"
            );
        }

        UUID token = UUID.randomUUID();

        String sql = """
        WITH candidate AS (
            SELECT c.id
            FROM device_commands c
            JOIN devices d
              ON d.tenant_id = c.tenant_id
             AND d.id = c.device_id
            WHERE c.tenant_id = ?
              AND c.device_id = ?
              AND c.status = 'queued'
              AND c.available_at <= NOW()
              AND c.expires_at > NOW()
              AND d.enabled = true
              AND d.protocol = c.protocol
            ORDER BY
                c.available_at,
                c.created_at,
                c.id
            FOR UPDATE OF c SKIP LOCKED
            LIMIT 1
        )
        UPDATE device_commands c
        SET status = 'sending',
            attempts = c.attempts + 1,
            claimed_at = NOW(),
            claim_token = ?
        FROM candidate
        WHERE c.id = candidate.id
          AND c.status = 'queued'
        RETURNING
            c.id,
            c.tenant_id,
            c.device_id,
            c.protocol,
            c.payload,
            c.attempts,
            c.claim_token
        """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setObject(1, tenantId);
            statement.setObject(2, deviceId);
            statement.setObject(3, token);

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    return Optional.empty();
                }

                ClaimedCommand command = new ClaimedCommand(
                        result.getObject("id", UUID.class),
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("device_id", UUID.class),
                        result.getString("protocol"),
                        result.getBytes("payload"),
                        result.getInt("attempts"),
                        result.getObject("claim_token", UUID.class)
                );

                if (result.next()) {
                    throw new SQLException(
                            "MULTIPLE_COMMANDS_CLAIMED"
                    );
                }

                return Optional.of(command);
            }
        }
    }

}
