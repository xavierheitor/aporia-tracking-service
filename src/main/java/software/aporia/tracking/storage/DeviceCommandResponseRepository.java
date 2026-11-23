package software.aporia.tracking.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

public final class DeviceCommandResponseRepository {

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
                result
            ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 'unknown')
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

            int inserted = statement.executeUpdate();

            if (inserted == 0) {
                return null;
            }

            return responseId;

        }

    }
}
