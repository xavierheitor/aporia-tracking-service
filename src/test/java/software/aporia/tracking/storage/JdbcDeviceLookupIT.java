package software.aporia.tracking.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.support.TestDatabase;
import com.zaxxer.hikari.HikariDataSource;

class JdbcDeviceLookupIT {

    private HikariDataSource createDataSource() {

        return TestDatabase.createDataSource();
    }

    private void createFixture(
            HikariDataSource dataSource,
            UUID tenantId,
            UUID deviceId,
            String identity,
            boolean enabled
    ) throws SQLException {

        try (Connection connection
                = dataSource.getConnection()) {

            connection.setAutoCommit(false);

            try (
                    PreparedStatement tenant
                    = connection.prepareStatement(
                            """
                                    INSERT INTO tenants (id, name)
                                    VALUES (?, ?)
                                    """
                    ); PreparedStatement device
                    = connection.prepareStatement(
                            """
                                    INSERT INTO devices
                                        (id, tenant_id, protocol, identity, enabled)
                                    VALUES (?, ?, ?, ?, ?)
                                    """
                    )) {

                        tenant.setObject(1, tenantId);
                        tenant.setString(2, "Test Tenant " + tenantId);
                        tenant.executeUpdate();

                        device.setObject(1, deviceId);
                        device.setObject(2, tenantId);
                        device.setString(3, "suntech");
                        device.setString(4, identity);
                        device.setBoolean(5, enabled);
                        device.executeUpdate();

                        connection.commit();

                    } catch (SQLException | RuntimeException e) {
                        connection.rollback();
                        throw e;
                    }
        }
    }

    private void deleteFixture(
            HikariDataSource dataSource,
            UUID tenantId,
            UUID deviceId
    ) throws SQLException {

        try (Connection connection
                = dataSource.getConnection()) {

            connection.setAutoCommit(false);

            try (
                    PreparedStatement deleteDevice
                    = connection.prepareStatement(
                            "DELETE FROM devices WHERE id = ?"
                    ); PreparedStatement deleteTenant
                    = connection.prepareStatement(
                            "DELETE FROM tenants WHERE id = ?"
                    )) {

                        deleteDevice.setObject(1, deviceId);
                        deleteDevice.executeUpdate();

                        deleteTenant.setObject(1, tenantId);
                        deleteTenant.executeUpdate();

                        connection.commit();

                    } catch (SQLException | RuntimeException e) {
                        connection.rollback();
                        throw e;
                    }
        }
    }

    @Test
    void shouldFindEnabledDevice() throws Exception {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        String identity = "9876543210";

        try (HikariDataSource dataSource = createDataSource()) {

            createFixture(
                    dataSource,
                    tenantId,
                    deviceId,
                    identity,
                    true
            );

            try {

                JdbcDeviceLookup lookup
                        = new JdbcDeviceLookup(dataSource);

                RegisteredDevice device
                        = lookup.findEnabled(
                                "suntech",
                                identity
                        ).orElseThrow();

                assertEquals(tenantId, device.tenantId());
                assertEquals(deviceId, device.deviceId());
                assertEquals("suntech", device.protocol());
                assertEquals(identity, device.identity());

            } finally {
                deleteFixture(dataSource, tenantId, deviceId);
            }
        }
    }

    @Test
    void shouldNotFindDisabledDevice() throws Exception {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        String identity = "9876543211";

        try (HikariDataSource dataSource = createDataSource()) {

            createFixture(
                    dataSource,
                    tenantId,
                    deviceId,
                    identity,
                    false
            );

            try {

                JdbcDeviceLookup lookup
                        = new JdbcDeviceLookup(dataSource);

                assertTrue(
                        lookup.findEnabled(
                                "suntech",
                                identity
                        ).isEmpty()
                );

            } finally {
                deleteFixture(dataSource, tenantId, deviceId);
            }
        }
    }

    @Test
    void shouldRejectUnknownDevice() {

        try (HikariDataSource dataSource = createDataSource()) {

            JdbcDeviceLookup lookup
                    = new JdbcDeviceLookup(dataSource);

            assertTrue(
                    lookup.findEnabled(
                            "suntech",
                            "0000000000"
                    ).isEmpty()
            );
        }
    }

    @Test
    void shouldRejectWrongProtocol() throws Exception {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        String identity = "9876543212";

        try (HikariDataSource dataSource = createDataSource()) {

            createFixture(
                    dataSource,
                    tenantId,
                    deviceId,
                    identity,
                    true
            );

            try {

                JdbcDeviceLookup lookup
                        = new JdbcDeviceLookup(dataSource);

                assertTrue(
                        lookup.findEnabled(
                                "teltonika",
                                identity
                        ).isEmpty()
                );

            } finally {
                deleteFixture(dataSource, tenantId, deviceId);
            }
        }
    }
}
