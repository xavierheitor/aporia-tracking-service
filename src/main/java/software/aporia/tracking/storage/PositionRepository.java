package software.aporia.tracking.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import software.aporia.tracking.core.Telemetry;

public class PositionRepository {

    public UUID save(
            Connection connection,
            UUID tenantId,
            UUID assetId,
            UUID deviceId,
            UUID messageId, int readingIndex,
            Telemetry telemetry,
            Instant receivedAt
    ) throws SQLException {
        if (!telemetry.hasPosition()) {
            throw new IllegalArgumentException("Telemetry does not contain a valid position");
        }

        UUID positionId = UUID.randomUUID();

        String sql = """
            INSERT INTO positions (
                id,
                tenant_id,
                asset_id,
                device_id,
                message_id,
                recorded_at,
                received_at,
                geom,
                speed_kph,
                heading,
                satellites,
                ignition, reading_index
            )
            VALUES (
                ?, ?, ?, ?, ?, ?, ?,
                ST_SetSRID(ST_MakePoint(?, ?), 4326),
                ?, ?, ?, ?, ?
            )
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, positionId);
            statement.setObject(2, tenantId);
            statement.setObject(3, assetId);
            statement.setObject(4, deviceId);
            statement.setObject(5, messageId);

            statement.setObject(
                    6,
                    java.sql.Timestamp.from(telemetry.recordedAt())
            );

            statement.setObject(
                    7,
                    java.sql.Timestamp.from(receivedAt)
            );

            statement.setDouble(8, telemetry.longitude());
            statement.setDouble(9, telemetry.latitude());

            statement.setObject(10, telemetry.speedKph());
            statement.setObject(11, telemetry.heading());
            statement.setObject(12, telemetry.satellites());
            statement.setObject(13, telemetry.ignition());

            statement.setInt(14, readingIndex);
            statement.executeUpdate();
        }

        return positionId;
    }

}
