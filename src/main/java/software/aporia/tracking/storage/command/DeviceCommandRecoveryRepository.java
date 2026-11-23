package software.aporia.tracking.storage.command;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

import software.aporia.tracking.command.dispatcher.DispatchOwner;

/** Expira comandos e reconcilia tentativas interrompidas respeitando o proprietário da transmissão. */
public final class DeviceCommandRecoveryRepository {

    /**
     * Marca como expirados os comandos que atingiram o prazo ainda na fila e devolve o número de
     * linhas afetadas.
     */
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

    /** Invalida o token de reservas antigas e classifica o resultado como incerto. */
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

    /** Recupera por idade as transmissões legadas sem proprietário registrado. */
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


    /** Reconcilia transmissões interrompidas do emissor que detém o bloqueio exclusivo. */
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

    /** Encerra por timeout a espera pela resposta dos comandos enviados cujo prazo venceu. */
    public int expireSent(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE device_commands SET status='timeout', error_code='RESPONSE_TIMEOUT', claim_token=NULL
                WHERE status='sent' AND expires_at <= now()
                """)) {
            return statement.executeUpdate();
        }
    }
}
