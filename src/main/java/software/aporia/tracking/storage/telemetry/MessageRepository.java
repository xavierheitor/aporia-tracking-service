package software.aporia.tracking.storage.telemetry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Registra a mensagem processada e identifica conteúdo já registrado no escopo da chave de
 * deduplicação.
 */
public class MessageRepository {

    /**
     * Retorna o UUID da mensagem inserida ou nulo quando a chave de deduplicação já existe.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @param rawPacketId identificador da recepção bruta
     * @param payloadHash resumo SHA-256 do conteúdo recebido
     * @return UUID da mensagem, ou nulo se o conteúdo já foi registrado
     * @throws SQLException se a operação JDBC falhar
     */
    public UUID save(
            Connection connection,
            UUID tenantId,
            UUID deviceId,
            UUID rawPacketId,
            String payloadHash
    ) throws SQLException {
        UUID messageId = UUID.randomUUID();

        // A restrição por tenant, dispositivo e hash resolve duplicatas inclusive entre workers concorrentes.
        String sql = """
            INSERT INTO messages (
                id,
                tenant_id,
                device_id,
                raw_packet_id,
                payload_hash
            )
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, device_id, payload_hash)
            DO NOTHING
            RETURNING id
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, messageId);
            statement.setObject(2, tenantId);
            statement.setObject(3, deviceId);
            statement.setObject(4, rawPacketId);
            statement.setString(5, payloadHash);

            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return result.getObject("id", UUID.class);
                }
            }
        }
        // Uma mensagem com o mesmo conteúdo já existe.
        return null;
    }
}
