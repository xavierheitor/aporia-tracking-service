package software.aporia.tracking.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;
import software.aporia.tracking.protocol.DeviceDescription;

public final class JdbcDeviceLookup {

    private final DataSource dataSource;

    public JdbcDeviceLookup(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public record Registration(RegisteredDevice device, boolean enabled) {}

    public Optional<RegisteredDevice> findEnabled(String protocol, String identity) {
        return find(protocol, identity).filter(Registration::enabled).map(Registration::device);
    }

    public Optional<Registration> find(
            String protocol,
            String identity
    ) {
        Objects.requireNonNull(protocol);
        Objects.requireNonNull(identity);

        String sql = """
            SELECT id, tenant_id, enabled
            FROM devices
            WHERE protocol = ?
              AND identity = ?
            """;

        try (
                Connection connection = dataSource.getConnection(); PreparedStatement statement
                = connection.prepareStatement(sql)) {
            statement.setString(1, protocol);
            statement.setString(2, identity);

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    return Optional.empty();
                }

                UUID deviceId
                        = result.getObject("id", UUID.class);

                UUID tenantId
                        = result.getObject("tenant_id", UUID.class);

                return Optional.of(new Registration(
                        new RegisteredDevice(tenantId, deviceId, protocol, identity), result.getBoolean("enabled")));
            }

        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DEVICE_LOOKUP_FAILED", e
            );
        }
    }

    public void recordDiscovery(String protocol, String identity,
                                DeviceDescription description) {
        String sql = """
                INSERT INTO device_discoveries AS q
                    (protocol, identity, manufacturer, device_type, wire_format, reported_model)
                SELECT ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (
                    SELECT 1 FROM devices WHERE protocol=? AND identity=?
                )
                ON CONFLICT (protocol, identity) DO UPDATE SET
                    last_seen_at=GREATEST(clock_timestamp(), q.last_seen_at),
                    observations=q.observations+1, wire_format=excluded.wire_format,
                    reported_model=coalesce(excluded.reported_model,q.reported_model)
                WHERE q.approved_at IS NULL
                """;
        try (var c = dataSource.getConnection(); var statement = c.prepareStatement(sql)) {
            statement.setString(1, protocol);
            statement.setString(2, identity);
            statement.setString(3, description.manufacturer());
            statement.setString(4, description.deviceType());
            statement.setString(5, description.wireFormat());
            statement.setString(6, description.reportedModel());
            statement.setString(7, protocol);
            statement.setString(8, identity);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("DEVICE_DISCOVERY_FAILED", e);
        }
    }
}
