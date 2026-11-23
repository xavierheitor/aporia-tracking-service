package software.aporia.tracking.command.dispatcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import software.aporia.tracking.protocol.suntech.FirmwareVersionRequest;
import software.aporia.tracking.storage.command.DeviceCommandDispatchRepository;
import software.aporia.tracking.storage.command.DeviceCommandRepository;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

import static org.junit.jupiter.api.Assertions.*;
import static software.aporia.tracking.command.dispatcher.CommandDispatcher.DispatchResult.*;

/** Exercita a reserva e o resultado persistido da transmissão, inclusive quando a gravação da conclusão falha. */
class CommandDispatcherIT {
    @TempDir Path directory;

    @FunctionalInterface
    private interface Writer {
        void send(byte[] bytes) throws IOException;
    }

    private CommandDispatcher dispatcher(DataSource db, DispatchOwner owner, TestFixtures device, Writer writer) {
        var endpoint = new CommandTransport.Endpoint() {
            @Override
            public String protocol() { return "suntech"; }
            @Override
            public String identity() { return device.deviceIdentity(); }
            @Override
            public void send(byte[] bytes) throws IOException { writer.send(bytes); }
        };
        return new CommandDispatcher(db, new DeviceCommandDispatchRepository(), (tenant, id) ->
                device.tenantId().equals(tenant) && device.deviceId().equals(id)
                        ? Optional.of(endpoint) : Optional.empty(), owner);
    }

    private UUID enqueue(DataSource db, TestFixtures device, byte[] payload) throws Exception {
        try (var c = db.getConnection()) {
            return new DeviceCommandRepository().enqueue(c, device.tenantId(), device.deviceId(),
                    "suntech", payload, Instant.now().plusSeconds(300));
        }
    }

    private UUID requestFirmware(DataSource db, TestFixtures device) throws Exception {
        try (var c = db.getConnection()) {
            c.setAutoCommit(false);
            UUID id = new DeviceCommandRepository().enqueueFirmwareVersion(c, device.tenantId(), device.deviceId(),
                    UUID.randomUUID(), UUID.randomUUID()).orElseThrow();
            c.commit();
            return id;
        }
    }

    private String status(DataSource db, TestFixtures device, UUID id) throws Exception {
        try (var c = db.getConnection()) {
            return new DeviceCommandRepository().findStatus(c, device.tenantId(), id).orElseThrow();
        }
    }

    @Test
    void persistsPartialWriteAndRejectsUnapprovedAndWrongIdentity() throws Exception {
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db);
             var owner = DispatchOwner.open(directory.resolve("gateway"))) {
            var prefix = new ByteArrayOutputStream();
            var dispatcher = dispatcher(db, owner, fixture, bytes -> {
                prefix.write(bytes, 0, 3);
                throw new IOException("simulated connection loss");
            });
            byte[] valid = FirmwareVersionRequest.encode(fixture.deviceIdentity());
            byte[] programming = ("PRG;" + fixture.deviceIdentity() + ";16;70#60\r").getBytes(StandardCharsets.US_ASCII);
            for (byte[] payload : new byte[][]{programming, FirmwareVersionRequest.encode("0000000001"), valid}) {
                UUID command = enqueue(db, fixture, payload);
                assertEquals(DELIVERY_FAILED, dispatcher.dispatchNext(fixture.tenantId(), fixture.deviceId()));
                assertEquals("delivery_failed", status(db, fixture, command));
            }
            assertEquals(0, prefix.size());
            UUID command = requestFirmware(db, fixture);
            assertEquals(DELIVERY_UNKNOWN, dispatcher.dispatchNext(fixture.tenantId(), fixture.deviceId()));
            assertEquals("delivery_unknown", status(db, fixture, command));
            assertEquals(NO_COMMAND, dispatcher.dispatchNext(fixture.tenantId(), fixture.deviceId()));
            assertEquals(3, prefix.size());
        }
    }

    @Test
    void databaseFailureAfterWriteIsVisibleAndNeverReplays() throws Exception {
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db);
             var owner = DispatchOwner.open(directory.resolve("gateway")); var c = db.getConnection()) {
            UUID command = requestFirmware(db, fixture);
            // O gatilho afeta somente a conclusão deste comando no schema isolado do teste.
            try (var sql = c.createStatement()) {
                sql.execute("ALTER TABLE device_commands ADD CONSTRAINT it_fail_mark_sent "
                        + "CHECK (id <> '" + command + "'::uuid OR status <> 'sent')");
                try {
                    var bytes = new ByteArrayOutputStream();
                    var dispatcher = dispatcher(db, owner, fixture, bytes::writeBytes);
                    var failure = assertThrows(java.sql.SQLException.class,
                            () -> dispatcher.dispatchNext(fixture.tenantId(), fixture.deviceId()));
                    assertEquals("23514", failure.getSQLState());
                    assertArrayEquals(FirmwareVersionRequest.encode(fixture.deviceIdentity()), bytes.toByteArray());
                    assertEquals("transmitting", status(db, fixture, command));
                    dispatcher.recover();
                    assertEquals("delivery_unknown", status(db, fixture, command));
                    assertEquals(NO_COMMAND, dispatcher.dispatchNext(fixture.tenantId(), fixture.deviceId()));
                } finally {
                    sql.execute("ALTER TABLE device_commands DROP CONSTRAINT it_fail_mark_sent");
                }
            }
        }
    }

    @Test
    void anotherOwnerCannotRevokePausedWriterAndRestartRecoversCrashAfterWrite() throws Exception {
        Path ownerFile = directory.resolve("gateway");
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db);
             var other = DispatchOwner.open(directory.resolve("other"))) {
            byte[] valid = FirmwareVersionRequest.encode(fixture.deviceIdentity());
            UUID command = requestFirmware(db, fixture);
            var bytes = new ByteArrayOutputStream();
            try (var owner = DispatchOwner.open(ownerFile); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var unavailable = assertThrows(IOException.class, () -> DispatchOwner.open(ownerFile));
                assertEquals("DISPATCH_OWNER_UNAVAILABLE", unavailable.getMessage());
                var authorized = new CountDownLatch(1);
                var resume = new CountDownLatch(1);
                var dispatcher = dispatcher(db, owner, fixture, payload -> {
                    authorized.countDown();
                    try {
                        if (!resume.await(5, TimeUnit.SECONDS)) throw new IOException("test timed out");
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                    bytes.writeBytes(payload);
                    throw new IllegalStateException("simulated process death after write");
                });
                var run = executor.submit(() -> dispatcher.dispatchNext(fixture.tenantId(), fixture.deviceId()));
                try {
                    assertTrue(authorized.await(5, TimeUnit.SECONDS));
                    try (var c = db.getConnection(); var s = c.prepareStatement("""
                            UPDATE device_commands SET transmission_started_at=now()-interval '1 day'
                            WHERE tenant_id=? AND id=? AND status='transmitting'
                            """)) {
                        s.setObject(1, fixture.tenantId());
                        s.setObject(2, command);
                        assertEquals(1, s.executeUpdate());
                    }
                    var second = dispatcher(db, other, fixture, payload -> fail("second writer"));
                    second.recover();
                    assertEquals("transmitting", status(db, fixture, command));
                    assertEquals(NO_COMMAND, second.dispatchNext(fixture.tenantId(), fixture.deviceId()));
                    assertEquals(0, bytes.size());
                } finally {
                    resume.countDown();
                }
                var crash = assertThrows(java.util.concurrent.ExecutionException.class, () -> run.get(5, TimeUnit.SECONDS));
                var cause = assertInstanceOf(IllegalStateException.class, crash.getCause());
                assertEquals("simulated process death after write", cause.getMessage());
                assertArrayEquals(valid, bytes.toByteArray());
            }
            try (var restarted = DispatchOwner.open(ownerFile)) {
                var dispatcher = dispatcher(db, restarted, fixture, payload -> fail("unexpected replay"));
                dispatcher.recover();
                assertEquals("delivery_unknown", status(db, fixture, command));
                assertEquals(NO_COMMAND, dispatcher.dispatchNext(fixture.tenantId(), fixture.deviceId()));
            }
        }
    }
}
