package software.aporia.tracking.storage.command;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Persiste a resposta de protocolo no escopo do dispositivo que a enviou e do pacote que a
 * transportou.
 */
public final class DeviceCommandResponseRepository {

    /**
     * Registra a resposta sem atribuir um comando de origem. Retorna o UUID criado ou nulo se o
     * pacote já tiver uma resposta.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @param rawPacketId identificador da recepção bruta
     * @param protocol identificador do protocolo do equipamento
     * @param responseType tipo da resposta de comando
     * @param payload bytes do conteúdo da mensagem
     * @return UUID da resposta, ou nulo se o pacote já tinha uma resposta
     * @throws SQLException se a operação JDBC falhar
     */
    public UUID save(
            Connection connection,
            UUID tenantId,
            UUID deviceId,
            UUID rawPacketId,
            String protocol,
            String responseType,
            byte[] payload
    ) throws SQLException {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(rawPacketId);
        Objects.requireNonNull(protocol);
        Objects.requireNonNull(responseType);
        Objects.requireNonNull(payload);

        if (!responseType.equals("RES")
                && !responseType.equals("RPR")) {
            throw new IllegalArgumentException(
                    "INVALID_RESPONSE_TYPE"
            );
        }

        if (payload.length == 0 || payload.length > 65536) {
            throw new IllegalArgumentException(
                    "INVALID_RESPONSE_PAYLOAD"
            );
        }

        UUID responseId = UUID.randomUUID();

        // RES e RPR podem chegar atrasadas ou repetidas. O pacote identifica a resposta, mas não prova qual comando a originou.
        String sql = """
            INSERT INTO device_command_responses (
                id,
                tenant_id,
                device_id,
                command_id,
                raw_packet_id,
                protocol,
                response_type,
                payload,
                result, received_at
            ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 'unknown',
                (SELECT received_at FROM raw_packets WHERE id=?))
            ON CONFLICT (raw_packet_id) DO NOTHING
            """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setObject(1, responseId);
            statement.setObject(2, tenantId);
            statement.setObject(3, deviceId);
            statement.setObject(4, rawPacketId);
            statement.setString(5, protocol);
            statement.setString(6, responseType);
            statement.setBytes(7, payload);
            statement.setObject(8, rawPacketId);

            int inserted = statement.executeUpdate();

            if (inserted == 0) {
                return null;
            }

            return responseId;

        }

    }
}
