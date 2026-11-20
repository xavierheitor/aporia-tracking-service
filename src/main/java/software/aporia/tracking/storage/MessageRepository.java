package software.aporia.tracking.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class MessageRepository {

    public UUID save(
            Connection connection,
            UUID tenantId,
            UUID deviceId,
            UUID rawPacketId,
            String payloadHash
    ) throws SQLException {
        UUID messageId = UUID.randomUUID();

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
