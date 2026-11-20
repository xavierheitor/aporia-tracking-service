
package software.aporia.tracking.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

public final class TestFixtures implements AutoCloseable {

    private final DataSource dataSource;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    private final String deviceIdentity;

    public TestFixtures(
            DataSource dataSource,
            String deviceIdentity
    ) throws SQLException {

        this.dataSource = dataSource;
        this.deviceIdentity = deviceIdentity;

        create();
    }

    private void create() throws SQLException {

        try (Connection connection =
                dataSource.getConnection()) {

            connection.setAutoCommit(false);

            try {
                execute(
                    connection,
                    "INSERT INTO tenants (id, name) VALUES (?, ?)",
                    tenantId,
                    "Integration Test"
                );

                execute(
                    connection,
                    """
                    INSERT INTO devices (
                        id, tenant_id, protocol, identity
                    )
                    VALUES (?, ?, ?, ?)
                    """,
                    deviceId,
                    tenantId,
                    "suntech",
                    deviceIdentity
                );

                execute(
                    connection,
                    """
                    INSERT INTO assets (
                        id, tenant_id, name
                    )
                    VALUES (?, ?, ?)
                    """,
                    assetId,
                    tenantId,
                    "Test Vehicle"
                );

                execute(
                    connection,
                    """
                    INSERT INTO assignments (
                        id, tenant_id, device_id,
                        asset_id, starts_at
                    )
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    tenantId,
                    deviceId,
                    assetId,
                    java.sql.Timestamp.from(
                        java.time.Instant.parse(
                            "2026-01-01T00:00:00Z"
                        )
                    )
                );

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private void execute(
            Connection connection,
            String sql,
            Object... parameters
    ) throws SQLException {

        try (PreparedStatement statement =
                connection.prepareStatement(sql)) {

            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }

            statement.executeUpdate();
        }
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID deviceId() {
        return deviceId;
    }

    public UUID assetId() {
        return assetId;
    }

    public String deviceIdentity() {
        return deviceIdentity;
    }

    @Override
    public void close() throws SQLException {

        try (Connection connection =
                dataSource.getConnection()) {

            connection.setAutoCommit(false);

            try {
                // Limpar os registros criados pelos testes.
                execute(
                    connection,
                    "DELETE FROM outbox_events WHERE tenant_id = ?",
                    tenantId
                );

                execute(
                    connection,
                    "DELETE FROM last_positions WHERE tenant_id = ?",
                    tenantId
                );

                execute(
                    connection,
                    "DELETE FROM positions WHERE tenant_id = ?",
                    tenantId
                );

                execute(
                    connection,
                    "DELETE FROM messages WHERE tenant_id = ?",
                    tenantId
                );

                execute(
                    connection,
                    "DELETE FROM raw_packets WHERE tenant_id = ?",
                    tenantId
                );

                execute(
                    connection,
                    "DELETE FROM assignments WHERE tenant_id = ?",
                    tenantId
                );

                execute(
                    connection,
                    "DELETE FROM assets WHERE tenant_id = ?",
                    tenantId
                );

                execute(
                    connection,
                    "DELETE FROM devices WHERE tenant_id = ?",
                    tenantId
                );

                execute(
                    connection,
                    "DELETE FROM tenants WHERE id = ?",
                    tenantId
                );

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }
}
