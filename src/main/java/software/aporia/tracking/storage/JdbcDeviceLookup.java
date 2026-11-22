package software.aporia.tracking.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

public final class JdbcDeviceLookup {

    private final DataSource dataSource;

    public JdbcDeviceLookup(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public Optional<RegisteredDevice> findEnabled(
            String protocol,
            String identity
    ) {
        Objects.requireNonNull(protocol);
        Objects.requireNonNull(identity);

        String sql = """
            SELECT id, tenant_id
            FROM devices
            WHERE protocol = ?
              AND identity = ?
              AND enabled = true
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

                if (result.next()) {
                    throw new IllegalStateException(
                            "AMBIGUOUS_DEVICE_IDENTITY"
                    );
                }

                return Optional.of(
                        new RegisteredDevice(
                                tenantId,
                                deviceId,
                                protocol,
                                identity
                        )
                );
            }

        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DEVICE_LOOKUP_FAILED", e
            );
        }
    }
}
