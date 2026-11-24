package software.aporia.tracking.storage.command;

import software.aporia.tracking.support.CommandTestFixtures;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.aporia.tracking.protocol.suntech.FirmwareVersionRequest;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;
import static org.junit.jupiter.api.Assertions.*;

/** Exercita a entrada auditada de produção, seus limites e o isolamento entre tenants. */
class DeviceCommandRepositoryIT {
    private final DeviceCommandRepository repository = new DeviceCommandRepository();

    /** Confere bytes, auditoria e prazo definidos pela operação permitida. */
    @Test
    void shouldEnqueueAuditedFirmwareRequest() throws Exception {
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db);
             var connection = db.getConnection()) {
            connection.setAutoCommit(false);
            UUID principal = UUID.randomUUID();
            UUID key = UUID.randomUUID();
            Instant before = Instant.now();
            UUID id = repository.enqueueFirmwareVersion(connection, fixture.tenantId(), fixture.deviceId(),
                    principal, key).orElseThrow();
            connection.commit();
            try (var query = connection.prepareStatement("""
                    SELECT protocol,payload,requested_by,request_key,attempts,status,expires_at
                    FROM device_commands WHERE tenant_id=? AND id=?
                    """)) {
                query.setObject(1, fixture.tenantId());
                query.setObject(2, id);
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("suntech", row.getString("protocol"));
                    assertArrayEquals(FirmwareVersionRequest.encode(fixture.deviceIdentity()), row.getBytes("payload"));
                    assertEquals(principal, row.getObject("requested_by", UUID.class));
                    assertEquals(key, row.getObject("request_key", UUID.class));
                    assertEquals(0, row.getInt("attempts"));
                    assertEquals("queued", row.getString("status"));
                    long delay = Duration.between(before, row.getObject("expires_at", OffsetDateTime.class).toInstant()).toSeconds();
                    assertTrue(delay >= 119 && delay <= 122);
                }
            }
        }
    }

    /** Um equipamento desabilitado não aceita uma nova solicitação. */
    @Test
    void shouldRejectDisabledDevice() throws Exception {
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db);
             var connection = db.getConnection()) {
            connection.setAutoCommit(false);
            try (var update = connection.prepareStatement("UPDATE devices SET enabled=false WHERE tenant_id=? AND id=?")) {
                update.setObject(1, fixture.tenantId());
                update.setObject(2, fixture.deviceId());
                assertEquals(1, update.executeUpdate());
            }
            assertTrue(repository.enqueueFirmwareVersion(connection, fixture.tenantId(), fixture.deviceId(),
                    UUID.randomUUID(), UUID.randomUUID()).isEmpty());
            connection.rollback();
        }
    }

    /** O tenant não pode solicitar comandos para o dispositivo de outro proprietário. */
    @Test
    void shouldRejectCrossTenantRequest() throws Exception {
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db);
             var connection = db.getConnection()) {
            connection.setAutoCommit(false);
            assertTrue(repository.enqueueFirmwareVersion(connection, UUID.randomUUID(), fixture.deviceId(),
                    UUID.randomUUID(), UUID.randomUUID()).isEmpty());
            UUID id = repository.enqueueFirmwareVersion(connection, fixture.tenantId(), fixture.deviceId(),
                    UUID.randomUUID(), UUID.randomUUID()).orElseThrow();
            connection.commit();
            assertEquals("queued", CommandTestFixtures.findStatus(connection, fixture.tenantId(), id).orElseThrow());
        }
    }

    /** Idempotência é verificada antes da capacidade: repetir uma requisição não ocupa outra vaga. */
    @Test
    void shouldPreserveIdempotencyWhenQueueIsFull() throws Exception {
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db);
             var connection = db.getConnection()) {
            connection.setAutoCommit(false);
            UUID principal = UUID.randomUUID();
            UUID key = UUID.randomUUID();
            UUID first = repository.enqueueFirmwareVersion(connection, fixture.tenantId(), fixture.deviceId(), principal, key).orElseThrow();
            for (int i = 1; i < 10; i++) {
                repository.enqueueFirmwareVersion(connection, fixture.tenantId(), fixture.deviceId(), principal, UUID.randomUUID()).orElseThrow();
            }
            connection.commit();
            assertEquals(first, repository.enqueueFirmwareVersion(connection, fixture.tenantId(), fixture.deviceId(), principal, key).orElseThrow());
            assertThrows(DeviceCommandRepository.QueueFullException.class, () -> repository.enqueueFirmwareVersion(
                    connection, fixture.tenantId(), fixture.deviceId(), principal, UUID.randomUUID()));
            connection.rollback();
        }
    }

    /** A entrada pública exige transação e os dados de auditoria completos. */
    @Test
    void shouldRequireTransactionAndAudit() throws Exception {
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db);
             var connection = db.getConnection()) {
            var error = assertThrows(IllegalStateException.class, () -> repository.enqueueFirmwareVersion(
                    connection, fixture.tenantId(), fixture.deviceId(), UUID.randomUUID(), UUID.randomUUID()));
            assertEquals("ENQUEUE_REQUIRES_TRANSACTION", error.getMessage());
            connection.setAutoCommit(false);
            assertThrows(NullPointerException.class, () -> repository.enqueueFirmwareVersion(
                    connection, fixture.tenantId(), fixture.deviceId(), null, UUID.randomUUID()));
            assertThrows(NullPointerException.class, () -> repository.enqueueFirmwareVersion(
                    connection, fixture.tenantId(), fixture.deviceId(), UUID.randomUUID(), null));
            connection.rollback();
        }
    }
}
