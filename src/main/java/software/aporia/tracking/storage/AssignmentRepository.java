package software.aporia.tracking.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class AssignmentRepository {

    public Optional<UUID> findAssetId(
            Connection connection,
            UUID tenantId,
            UUID deviceId,
            Instant recordedAt
    ) throws SQLException {
        String sql = """
            SELECT asset_id
            FROM assignments
            WHERE tenant_id = ?
              AND device_id = ?
              AND starts_at <= ?
              AND (ends_at IS NULL OR ends_at > ?)
            LIMIT 1
            """;

               try (PreparedStatement statement =
                connection.prepareStatement(sql)) {

            statement.setObject(1, tenantId);
            statement.setObject(2, deviceId);
            statement.setObject(
                3,
                java.sql.Timestamp.from(recordedAt)
            );
            statement.setObject(
                4,
                java.sql.Timestamp.from(recordedAt)
            );

             try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return Optional.of(
                        result.getObject("asset_id", UUID.class)
                    );
                }
            }
        }

        return Optional.empty();
    }
}
