package software.aporia.tracking.gateway;

import com.zaxxer.hikari.HikariDataSource;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.aporia.tracking.protocol.FirmwareVersionRequest;
import software.aporia.tracking.storage.DeviceCommandRepository;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;
import software.aporia.tracking.worker.WorkerApplication;

import static org.junit.jupiter.api.Assertions.*;

class GatewayCommandCycleIT {
    @TempDir Path runtime;

    private record Service(Process process) implements AutoCloseable {
        @Override public void close() {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly().waitFor();
                    fail("Service did not shut down gracefully");
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while stopping test service", interrupted);
            }
        }
    }

    private Service start(Class<?> main, HikariDataSource db, Path log) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java");
        var builder = new ProcessBuilder(java.toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), main.getName());
        var env = builder.environment();
        env.put("TRACKING_DB_URL", db.getJdbcUrl());
        env.put("TRACKING_DB_USER", db.getUsername());
        env.put("TRACKING_DB_PASSWORD", db.getPassword());
        env.put("TRACKING_TCP_BIND", "127.0.0.1");
        env.put("TRACKING_TCP_PORT", "0");
        env.put("TRACKING_PROTOCOL", "auto");
        env.put("TRACKING_DISPATCH_OWNER_FILE", runtime.resolve("owner").toString());
        return new Service(builder.redirectErrorStream(true).redirectOutput(log.toFile()).start());
    }

    // Os processos são externos à JVM de teste. A espera intercala consultas até o prazo de dez segundos.
    @SuppressWarnings("SleepWhileInLoop")
    private void await(String description, Callable<Boolean> predicate) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            if (predicate.call()) return;
            Thread.sleep(25);
        }
        fail(description);
    }

    private int count(Connection c, String table, UUID tenant) throws Exception {
        try (var s = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id=?")) {
            s.setObject(1, tenant);
            try (var rows = s.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private UUID enqueue(HikariDataSource db, TestFixtures fixture, UUID tenant, UUID principal, UUID key)
            throws Exception {
        try (var c = db.getConnection()) {
            c.setAutoCommit(false);
            var id = new DeviceCommandRepository().enqueueFirmwareVersion(c, tenant, fixture.deviceId(), principal, key);
            c.commit();
            return id.orElse(null);
        }
    }

    @Test
    void offlineQueueReconnectAndUncorrelatedResponsesThroughProductionEntryPoints() throws Exception {
        Path log = runtime.resolve("gateway.log");
        var listener = Pattern.compile("Tracking TCP Gateway listening on 127\\.0\\.0\\.1:(\\d+)");
        var repository = new DeviceCommandRepository();
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db);
             var gateway = start(TcpGateway.class, db, log);
             var worker = start(WorkerApplication.class, db, runtime.resolve("worker.log"));
             var c = db.getConnection()) {
            await("Gateway did not start", () -> {
                assertTrue(gateway.process().isAlive());
                return listener.matcher(Files.readString(log)).find();
            });
            var match = listener.matcher(Files.readString(log));
            assertTrue(match.find());
            int port = Integer.parseInt(match.group(1));
            try (var teltonika = new TestFixtures(db)) {
                verifyAvl(port, c, teltonika);
            }
            UUID tenant = fixture.tenantId();
            UUID principal = UUID.randomUUID();
            UUID key = UUID.randomUUID();
            assertNull(enqueue(db, fixture, UUID.randomUUID(), principal, key));
            UUID first = enqueue(db, fixture, tenant, principal, key);
            assertNotNull(first);
            assertEquals(first, enqueue(db, fixture, tenant, principal, key));
            UUID second = enqueue(db, fixture, tenant, principal, UUID.randomUUID());
            assertNotEquals(first, second);
            assertEquals("queued", repository.findStatus(c, tenant, first).orElseThrow());

            String identity = fixture.deviceIdentity();
            byte[] alive = ("ALV;" + identity + "\r").getBytes(StandardCharsets.US_ASCII);
            byte[] expected = FirmwareVersionRequest.encode(identity);
            try (var tracker = new Socket("127.0.0.1", port)) {
                tracker.setSoTimeout(8000);
                tracker.getOutputStream().write(alive);
                assertArrayEquals(expected, tracker.getInputStream().readNBytes(expected.length));
                await("First write was not committed", () -> repository.findStatus(c, tenant, first).orElseThrow().equals("sent"));
                assertEquals("queued", repository.findStatus(c, tenant, second).orElseThrow());
            }
            // Move the first response deadline into the past while the tracker is offline.
            try (var s = c.prepareStatement("""
                    UPDATE device_commands SET created_at=now()-interval '3 minutes',
                    expires_at=now()-interval '1 second' WHERE tenant_id=? AND id=? AND status='sent'
                    """)) {
                s.setObject(1, tenant);
                s.setObject(2, first);
                assertEquals(1, s.executeUpdate());
            }
            await("First command did not time out", () -> repository.findStatus(c, tenant, first).orElseThrow().equals("timeout"));
            try (var tracker = new Socket("127.0.0.1", port)) {
                tracker.setSoTimeout(8000);
                tracker.getOutputStream().write(alive);
                assertArrayEquals(expected, tracker.getInputStream().readNBytes(expected.length));
                String res = "RES;" + identity + ";03;04;01;STB.201.1.1.11\r";
                String pos = "RES;STT;" + identity + ";013831;20261120;08:45:00;-16.6700;-49.2500;45.5;1\r";
                tracker.getOutputStream().write((res + pos + "RPR;" + identity + ";OK;10;13#0\r" + res)
                        .getBytes(StandardCharsets.US_ASCII));
            }
            await("Worker did not process replies", () -> {
                assertTrue(worker.process().isAlive());
                return count(c, "device_command_responses", tenant) == 4;
            });
            assertEquals(1, count(c, "positions", tenant));
            assertEquals("sent", repository.findStatus(c, tenant, second).orElseThrow());
            assertEquals("timeout", repository.findStatus(c, tenant, first).orElseThrow());
            try (var s = c.prepareStatement("""
                    SELECT count(*) FROM device_command_responses
                    WHERE tenant_id=? AND device_id=? AND command_id IS NULL AND result='unknown'
                    """)) {
                s.setObject(1, tenant);
                s.setObject(2, fixture.deviceId());
                try (var responses = s.executeQuery()) {
                    responses.next();
                    assertEquals(4, responses.getInt(1));
                }
            }
        }
    }
    private void verifyAvl(int port, Connection c, TestFixtures fixture) throws Exception {
        String imei = "35000" + fixture.deviceIdentity();
        var hex = java.util.HexFormat.of();
        byte[] login = java.nio.ByteBuffer.allocate(17).putShort((short)15)
                .put(imei.getBytes(StandardCharsets.US_ASCII)).array();
        try (var denied = new Socket("127.0.0.1", port)) {
            denied.setSoTimeout(3000);
            denied.getOutputStream().write(login);
            assertEquals(0, denied.getInputStream().read());
        }
        try (var update = c.prepareStatement("UPDATE devices SET identity=?,protocol='teltonika' WHERE tenant_id=? AND id=?")) {
            update.setString(1, imei);
            update.setObject(2, fixture.tenantId());
            update.setObject(3, fixture.deviceId());
            assertEquals(1, update.executeUpdate());
        }
        // Two fixes per batch, southern/western coordinates, ignition and extended variable-size IO.
        byte[] codec8 = hex.parseHex("00000000000000430802000001a3185c500000e3fc5e80f268ff00007b001f070019000101ef01000000000001a3185c500100e3fc5e80f268ff00007b001f070019000101ef010000000200005a0c");
        byte[] extended = hex.parseHex("00000000000000638e02000001a3185c500000e3fc5e80f268ff00007b001f07001900000002000100ef01000000000000000101000003616263000001a3185c500100e3fc5e80f268ff00007b001f07001900000002000100ef01000000000000000101000003616263020000dd1a");
        try (var tracker = new Socket("127.0.0.1", port)) {
            tracker.setSoTimeout(5000);
            tracker.getOutputStream().write(login);
            assertEquals(1, tracker.getInputStream().read());
            tracker.getOutputStream().write(0xff);
            for (byte[] bytes : new byte[][]{codec8, extended, extended}) {
                tracker.getOutputStream().write(bytes);
                assertArrayEquals(new byte[]{0,0,0,2}, tracker.getInputStream().readNBytes(4));
            }
            // Revalidation at ingest must fail closed even after a successful login.
            try (var disable = c.prepareStatement("UPDATE devices SET enabled=false WHERE tenant_id=? AND id=?")) {
                disable.setObject(1, fixture.tenantId());
                disable.setObject(2, fixture.deviceId());
                assertEquals(1, disable.executeUpdate());
            }
            tracker.getOutputStream().write(codec8);
            assertEquals(-1, tracker.getInputStream().read());
        }
        await("Worker did not persist both batches", () -> count(c, "positions", fixture.tenantId()) == 4);
        assertEquals(3, count(c, "raw_packets", fixture.tenantId()));
        assertEquals(2, count(c, "messages", fixture.tenantId()));
        try (var s = c.prepareStatement("""
                SELECT count(*),count(DISTINCT reading_index) FROM positions WHERE tenant_id=?
                AND ignition AND ST_X(geom)=-47 AND ST_Y(geom)=-22.8
                """)) {
            s.setObject(1, fixture.tenantId());
            try (var rows = s.executeQuery()) {
                rows.next();
                assertEquals(4, rows.getInt(1));
                assertEquals(2, rows.getInt(2));
            }
        }
    }
}
