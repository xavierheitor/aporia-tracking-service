package software.aporia.tracking.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import software.aporia.tracking.protocol.DeviceDescription;
import software.aporia.tracking.support.TestFixtures;

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
    void concurrentApprovalsAssignDiscoveryToExactlyOneTenant() throws Exception {
        try (var db = createDataSource(); var first = new TestFixtures(db); var second = new TestFixtures(db);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String identity = "35000" + first.deviceIdentity();
            var lookup = new JdbcDeviceLookup(db);
            lookup.recordDiscovery("teltonika", identity, new DeviceDescription("Teltonika", "gps_tracker", "Codec 8", null));
            var barrier = new CyclicBarrier(2);
            var a = executor.submit(() -> approve(db, identity, first.tenantId(), barrier));
            var b = executor.submit(() -> approve(db, identity, second.tenantId(), barrier));
            boolean firstApproved = a.get(10, TimeUnit.SECONDS);
            assertEquals(!firstApproved, b.get(10, TimeUnit.SECONDS));
            var registered = lookup.findEnabled("teltonika", identity).orElseThrow();
            assertEquals(firstApproved ? first.tenantId() : second.tenantId(), registered.tenantId());
        }
    }

    private boolean approve(HikariDataSource db, String identity, UUID tenant, CyclicBarrier barrier) throws Exception {
        try (var c = db.getConnection(); var query = c.prepareStatement("""
                SELECT approve_device_discovery(id, ?) FROM device_discoveries WHERE protocol='teltonika' AND identity=?
                """)) {
            query.setObject(1, tenant);
            query.setString(2, identity);
            barrier.await(5, TimeUnit.SECONDS);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                return true;
            } catch (SQLException conflict) {
                if (conflict.getMessage().contains("DISCOVERY_ALREADY_APPROVED")) return false;
                throw conflict;
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
