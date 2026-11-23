package software.aporia.tracking.storage.command;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reserva comandos e condiciona autorização e conclusão ao token da tentativa, para impedir
 * atualizações por um emissor obsoleto.
 */
public final class DeviceCommandDispatchRepository {

    /**
     * Reserva identificada pelo token exigido nas transições seguintes. Os bytes são copiados
     * para preservar a tentativa.
     */
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
                    + "]";
        }
    }

    /** Reserva um comando elegível na transação do chamador. */
    public Optional<ClaimedCommand> claimNext(
            Connection connection
    ) throws SQLException {

        return claim(connection, null, null);
    }

    /**
     * Registra a escrita local concluída se a tentativa ainda detiver o token e estiver em
     * transmissão.
     */
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
            sent_at = NOW(), claim_token = NULL
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

    /** Condiciona o início da transmissão ao estado da reserva e ao token da tentativa. */
    public boolean authorizeTransmission(
            Connection connection,
            UUID tenantId,
            UUID commandId,
            UUID claimToken
    ) throws SQLException {

        return authorizeTransmission(connection, tenantId, commandId, claimToken, null, null);
    }

    /** Condiciona o início da transmissão ao estado da reserva e ao token da tentativa. */
    public boolean authorizeTransmission(Connection connection, UUID tenantId, UUID commandId,
                                         UUID claimToken, UUID owner, String sessionIdentity) throws SQLException {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(commandId);
        Objects.requireNonNull(claimToken);

        String sql = """
        UPDATE device_commands
        SET status = 'transmitting',
            transmission_started_at = NOW(), dispatch_owner = ?
        WHERE tenant_id = ?
          AND id = ?
          AND claim_token = ?
          AND status = 'sending'
          AND expires_at > clock_timestamp()
          AND (CAST(? AS uuid) IS NULL OR (requested_by IS NOT NULL AND request_key IS NOT NULL))
          AND EXISTS (SELECT 1 FROM devices d WHERE d.id = device_commands.device_id
                      AND d.tenant_id = device_commands.tenant_id AND d.enabled
                      AND d.protocol = device_commands.protocol
                      AND (CAST(? AS varchar) IS NULL OR d.identity=?))
        """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setObject(1, owner);
            statement.setObject(2, tenantId);
            statement.setObject(3, commandId);
            statement.setObject(4, claimToken);
            statement.setObject(5, owner);
            statement.setString(6, sessionIdentity);
            statement.setString(7, sessionIdentity);

            return statement.executeUpdate() == 1;
        }
    }

    /** Restringe a reserva ao tenant e dispositivo cuja sessão o dispatcher localizou. */
    public Optional<ClaimedCommand> claimNextForDevice(
            Connection connection,
            UUID tenantId,
            UUID deviceId
    ) throws SQLException {

        return claim(connection, Objects.requireNonNull(tenantId), Objects.requireNonNull(deviceId));
    }

    private Optional<ClaimedCommand> claim(Connection connection, UUID tenantId, UUID deviceId)
            throws SQLException {
        if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                    "CLAIM_REQUIRES_TRANSACTION"
            );
        }

        String select = """
            SELECT c.id
            FROM device_commands c
            JOIN devices d
              ON d.tenant_id = c.tenant_id
             AND d.id = c.device_id
            WHERE %s c.status = 'queued'
              AND c.available_at <= NOW()
              AND c.expires_at > NOW()
              AND d.enabled = true
              AND d.protocol = c.protocol
              AND NOT EXISTS (SELECT 1 FROM device_commands active
                              WHERE active.tenant_id = c.tenant_id AND active.device_id = c.device_id
                                AND active.status IN ('sending', 'transmitting', 'sent'))
            ORDER BY
                c.available_at,
                c.created_at,
                c.id
            FOR UPDATE OF c, d SKIP LOCKED
            LIMIT 1
        """.formatted(deviceId == null ? "" : "c.tenant_id = ? AND c.device_id = ? AND");

        UUID candidate;
        try (var statement = connection.prepareStatement(select)) {
            if (deviceId != null) {
                statement.setObject(1, tenantId);
                statement.setObject(2, deviceId);
            }
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                candidate = rows.getObject(1, UUID.class);
            }
        }

        // Após obter o bloqueio, a próxima consulta em READ COMMITTED enxerga reservas recém-confirmadas.
        String update = """
                UPDATE device_commands c SET status='sending', attempts=c.attempts+1,
                    claimed_at=now(), claim_token=?
                WHERE c.id=? AND c.status='queued' AND c.expires_at > clock_timestamp()
                AND NOT EXISTS (SELECT 1 FROM device_commands active
                                WHERE active.tenant_id=c.tenant_id AND active.device_id=c.device_id
                                  AND active.status IN ('sending','transmitting','sent'))
                RETURNING id,tenant_id,device_id,protocol,payload,attempts,claim_token
                """;
        try (var statement = connection.prepareStatement(update)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, candidate);
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

    /**
     * Motivo usado para distinguir recusa antes do envio de falha potencialmente posterior à
     * escrita.
     */
    public enum Failure {
        COMMAND_POLICY_DENIED, AUTHORIZATION_REJECTED, TCP_WRITE_UNCERTAIN
    }

    /**
     * Registra o desfecho compatível com a etapa da falha e indica se a tentativa ainda podia
     * alterar o comando.
     */
    public boolean finishFailure(Connection connection, ClaimedCommand command, Failure reason)
            throws SQLException {
        boolean writing = reason == Failure.TCP_WRITE_UNCERTAIN;
        String transition = writing
                ? "status='delivery_unknown', delivery_unknown_at=now()"
                : "status='delivery_failed'";
        String sql = "UPDATE device_commands SET claim_token=NULL, error_code=?, " + transition
                + " WHERE tenant_id=? AND device_id=? AND id=? AND claim_token=? AND status=?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, reason.name());
            statement.setObject(2, command.tenantId());
            statement.setObject(3, command.deviceId());
            statement.setObject(4, command.id());
            statement.setObject(5, command.claimToken());
            statement.setString(6, writing ? "transmitting" : "sending");
            return statement.executeUpdate() == 1;
        }
    }
}
