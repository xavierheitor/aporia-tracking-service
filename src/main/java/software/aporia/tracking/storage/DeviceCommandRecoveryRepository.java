package software.aporia.tracking.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import software.aporia.tracking.command.dispatcher.DispatchOwner;
import java.util.Objects;

public final class DeviceCommandRecoveryRepository {

    public int expireQueued(
            Connection connection
    ) throws SQLException {

        Objects.requireNonNull(connection);

        String sql = """
            UPDATE device_commands
            SET status = 'timeout',
                error_code = 'COMMAND_EXPIRED_IN_QUEUE'
            WHERE status = 'queued'
              AND expires_at <= NOW()
            """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            return statement.executeUpdate();
        }
    }

    public int markStaleSendingAsUnknown(
            Connection connection,
            Duration staleAfter
    ) throws SQLException {

        Objects.requireNonNull(connection);
        Objects.requireNonNull(staleAfter);

        if (staleAfter.isZero()
                || staleAfter.isNegative()) {
            throw new IllegalArgumentException(
                    "INVALID_STALE_DURATION"
            );
        }

        if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                    "RECOVERY_REQUIRES_TRANSACTION"
            );
        }

        String sql = """
            UPDATE device_commands
            SET status = 'delivery_unknown',
                delivery_unknown_at = NOW(),
                claim_token = NULL,
                error_code = 'SENDING_LEASE_EXPIRED'
            WHERE status = 'sending'
              AND claimed_at IS NOT NULL
              AND claimed_at < NOW() - (
                  CAST(? AS DOUBLE PRECISION)
                  * INTERVAL '1 second'
              )
            """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setDouble(
                    1,
                    staleAfter.toNanos() / 1_000_000_000.0
            );

            return statement.executeUpdate();
        }
    }

    public int markStaleTransmittingAsUnknown(
            Connection connection,
            Duration staleAfter
    ) throws SQLException {

        Objects.requireNonNull(connection);
        Objects.requireNonNull(staleAfter);

        if (staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException(
                    "INVALID_STALE_DURATION"
            );
        }

        if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                    "RECOVERY_REQUIRES_TRANSACTION"
            );
        }

        String sql = """
        UPDATE device_commands
        SET status = 'delivery_unknown',
            delivery_unknown_at = NOW(),
            claim_token = NULL,
            error_code = 'TRANSMISSION_LEASE_EXPIRED'
        WHERE status = 'transmitting'
          AND dispatch_owner IS NULL
          AND transmission_started_at IS NOT NULL
          AND transmission_started_at <
              NOW() - (
                  CAST(? AS DOUBLE PRECISION)
                  * INTERVAL '1 second'
              )
        """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setDouble(
                    1,
                    staleAfter.toNanos() / 1_000_000_000.0
            );

            return statement.executeUpdate();
        }
    }


    public int recoverOwner(Connection connection, DispatchOwner owner) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE device_commands SET status='delivery_unknown', delivery_unknown_at=now(),
                    claim_token=NULL, error_code='TRANSMISSION_INTERRUPTED'
                WHERE status='transmitting' AND dispatch_owner=?
                """)) {
            statement.setObject(1, owner.id());
            return statement.executeUpdate();
        }
    }

    public int expireSent(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE device_commands SET status='timeout', error_code='RESPONSE_TIMEOUT', claim_token=NULL
                WHERE status='sent' AND expires_at <= now()
                """)) {
            return statement.executeUpdate();
        }
    }
}
