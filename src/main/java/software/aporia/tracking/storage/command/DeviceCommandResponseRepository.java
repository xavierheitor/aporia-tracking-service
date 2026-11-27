package software.aporia.tracking.storage.command;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Persiste a resposta de protocolo no escopo do dispositivo que a enviou e do pacote que a
 * transportou.
 *
 * <p>Não correlaciona a resposta a um comando: a coluna {@code command_id} fica nula e o resultado permanece
 * {@code unknown}. A recepção bruta é a chave de idempotência, e as chaves estrangeiras compostas garantem
 * que pacote, dispositivo e tenant pertencem ao mesmo cadastro. A operação usa a conexão do chamador e não
 * confirma a transação.</p>
 */
public final class DeviceCommandResponseRepository {

    /**
     * Registra a resposta sem atribuir um comando de origem. Retorna o UUID criado ou nulo se o
     * pacote já tiver uma resposta.
     *
     * <p>Aceita somente {@code RES} e {@code RPR} e payload entre 1 e 65536 bytes. O {@code received_at} é
     * herdado do pacote bruto, preservando a ordem de chegada. {@code ON CONFLICT (raw_packet_id) DO NOTHING}
     * torna a gravação idempotente por pacote e devolve nulo na repetição; as chaves estrangeiras compostas
     * rejeitam referências de outro tenant ou dispositivo. Não confirma a transação.</p>
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @param rawPacketId identificador da recepção bruta
     * @param protocol identificador do protocolo do equipamento
     * @param responseType tipo da resposta de comando
     * @param payload bytes do conteúdo da mensagem
     * @return UUID da resposta, ou nulo se o pacote já tinha uma resposta
     * @throws SQLException             se a operação JDBC falhar
     * @throws IllegalArgumentException se o tipo ou o tamanho do payload for
     *                                  inválido
     * @throws NullPointerException     se um argumento obrigatório for nulo
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
        // O received_at é herdado do pacote e ON CONFLICT ignora a repetição do mesmo pacote.
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

            // Zero linhas indica conflito no pacote, não falha de gravação.
            if (inserted == 0) {
                return null;
            }

            return responseId;

        }

    }
}
