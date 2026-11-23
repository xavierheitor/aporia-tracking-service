package software.aporia.tracking.storage.command;

import software.aporia.tracking.protocol.suntech.FirmwareVersionRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persiste comandos por tenant e dispositivo e controla a idempotência das solicitações de firmware. */
public final class DeviceCommandRepository {

    /**
     * Insere uma solicitação legada sem auditoria de solicitante. O dispatcher exige a entrada
     * auditada para transmitir.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @param protocol identificador do protocolo do equipamento
     * @param payload bytes do conteúdo da mensagem
     * @param expiresAt instante limite da solicitação
     * @return UUID do comando inserido
     * @throws SQLException se a operação JDBC falhar
     */
    public UUID enqueue(
            Connection connection,
            UUID tenantId,
            UUID deviceId,
            String protocol,
            byte[] payload,
            Instant expiresAt
    ) throws SQLException {
        return enqueue(connection, tenantId, deviceId, protocol, payload, expiresAt, null, null);
    }

    /**
     * Sob o bloqueio do dispositivo, reaproveita a requisição idempotente ou cria a consulta de
     * firmware auditada na transação aberta.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @param principalId identificador do solicitante auditado
     * @param requestKey chave que identifica repetições da mesma solicitação
     * @return UUID da solicitação criada ou existente, ou vazio para dispositivo inelegível
     * @throws SQLException se a operação JDBC falhar
     * @throws QueueFullException se a fila já contiver dez consultas pendentes para o equipamento
     * @throws IllegalStateException se a conexão usar autocommit
     */
    public Optional<UUID> enqueueFirmwareVersion(Connection connection, UUID tenantId, UUID deviceId,
                                                 UUID principalId, UUID requestKey) throws SQLException {
        Objects.requireNonNull(principalId);
        Objects.requireNonNull(requestKey);
        if (connection.getAutoCommit()) throw new IllegalStateException("ENQUEUE_REQUIRES_TRANSACTION");
        try (var statement = connection.prepareStatement("""
                SELECT identity FROM devices WHERE tenant_id=? AND id=? AND protocol='suntech'
                AND enabled FOR UPDATE
                """)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, deviceId);
            try (var row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                // O bloqueio da linha serializa a verificação de idempotência e do limite da fila até o commit.
                try (var previous = connection.prepareStatement("""
                        SELECT id FROM device_commands WHERE tenant_id=? AND device_id=?
                        AND requested_by=? AND request_key=?
                        """)) {
                    previous.setObject(1, tenantId);
                    previous.setObject(2, deviceId);
                    previous.setObject(3, principalId);
                    previous.setObject(4, requestKey);
                    try (var ids = previous.executeQuery()) {
                        if (ids.next()) return Optional.of(ids.getObject(1, UUID.class));
                    }
                }
                try (var capacity = connection.prepareStatement("""
                        SELECT count(*) FROM device_commands WHERE tenant_id=? AND device_id=? AND status='queued'
                        """)) {
                    capacity.setObject(1, tenantId);
                    capacity.setObject(2, deviceId);
                    try (var count = capacity.executeQuery()) {
                        count.next();
                        if (count.getInt(1) >= 10) throw new QueueFullException();
                    }
                }
                byte[] payload = FirmwareVersionRequest.encode(row.getString(1));
                return Optional.of(enqueue(connection, tenantId, deviceId, "suntech", payload,
                        Instant.now().plusSeconds(120), principalId, requestKey));
            }
        }
    }

    /** Indica que o dispositivo atingiu o limite de consultas enfileiradas. */
    public static final class QueueFullException extends IllegalStateException {
        /** Versão da forma serializada desta exceção. */
        @java.io.Serial
        private static final long serialVersionUID = 1L;
    }

    /**
     * Persiste o comando no escopo do dispositivo habilitado e preserva os dados de auditoria
     * quando fornecidos.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @param protocol identificador do protocolo do equipamento
     * @param payload bytes do conteúdo da mensagem
     * @param expiresAt instante limite da solicitação
     * @param principalId identificador do solicitante auditado
     * @param requestKey chave que identifica repetições da mesma solicitação
     * @return UUID do comando inserido
     * @throws SQLException se a operação JDBC falhar
     */
    private UUID enqueue(Connection connection, UUID tenantId, UUID deviceId, String protocol,
                         byte[] payload, Instant expiresAt, UUID principalId, UUID requestKey) throws SQLException {
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
                expires_at, requested_by, request_key
            )
            SELECT
                ?, d.tenant_id, d.id, d.protocol,
                ?, 'queued', ?, ?, ?
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
            statement.setObject(4, principalId);
            statement.setObject(5, requestKey);
            statement.setObject(6, tenantId);
            statement.setObject(7, deviceId);
            statement.setString(8, protocol);

            int inserted = statement.executeUpdate();

            if (inserted != 1) {
                throw new IllegalArgumentException(
                        "UNKNOWN_OR_DISABLED_DEVICE"
                );
            }
        }

        return commandId;
    }

    /**
     * Consulta o estado do comando apenas no tenant informado.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param tenantId identificador do tenant que delimita a operação
     * @param commandId identificador do comando na fila
     * @return estado persistido, se o comando existir no tenant
     * @throws SQLException se a operação JDBC falhar
     */
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
