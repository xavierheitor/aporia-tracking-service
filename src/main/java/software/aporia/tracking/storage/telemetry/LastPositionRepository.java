package software.aporia.tracking.storage.telemetry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/** Atualiza a posição mais recente do ativo segundo a ordem temporal das leituras. */
public class LastPositionRepository {

    /**
     * Insere a primeira posição ou avança o marcador para uma leitura mais recente. Retorna se
     * houve alteração.
     */
    public boolean update(
            Connection connection,
            UUID tenantId,
            UUID assetId,
            UUID positionId,
            Instant recordedAt,
            Instant receivedAt
    ) throws SQLException {
        String sql = """
            INSERT INTO last_positions (
                tenant_id,
                asset_id,
                position_id,
                recorded_at,
                received_at
            )
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, asset_id)
            DO UPDATE SET
                position_id = EXCLUDED.position_id,
                recorded_at = EXCLUDED.recorded_at,
                received_at = EXCLUDED.received_at
            WHERE EXCLUDED.recorded_at >
                  last_positions.recorded_at
            """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setObject(1, tenantId);
            statement.setObject(2, assetId);
            statement.setObject(3, positionId);

            statement.setObject(
                    4,
                    java.sql.Timestamp.from(recordedAt)
            );

            statement.setObject(
                    5,
                    java.sql.Timestamp.from(receivedAt)
            );

            return statement.executeUpdate() > 0;
        }
    }
}
