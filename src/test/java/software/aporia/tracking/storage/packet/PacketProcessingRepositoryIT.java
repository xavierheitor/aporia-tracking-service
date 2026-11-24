package software.aporia.tracking.storage.packet;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import software.aporia.tracking.core.ReceivedFrame;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;
import software.aporia.tracking.worker.TrackingWorker;

import static org.junit.jupiter.api.Assertions.*;

/** Exercita o escopo da reserva e as garantias transacionais extraídas do worker. */
class PacketProcessingRepositoryIT {
    /** O filtro impede que a chamada consuma ou atualize um pacote de outro tenant. */
    @Test
    void shouldOnlyProcessSelectedTenant() throws Exception {
        try (var db = TestDatabase.createDataSource();
             var first = new TestFixtures(db); var second = new TestFixtures(db)) {
            UUID firstPacket = enqueue(db, first);
            UUID secondPacket = enqueue(db, second);
            var repository = new PacketProcessingRepository(db);
            assertEquals(secondPacket, repository.processNext(second.tenantId(), Set.of("suntech-ascii-v3"), processing -> {
                assertEquals(second.tenantId(), processing.packet().tenantId());
                assertEquals(second.deviceId(), processing.packet().deviceId());
                processing.markProcessed();
                return processing.packet().id();
            }).orElseThrow());
            assertPacket(db, first, firstPacket, "pending", 0);
            assertPacket(db, second, secondPacket, "processed", 1);
            assertTrue(repository.processNext(second.tenantId(), Set.of("suntech-ascii-v3"), processing -> {
                fail("O tenant não tem outro pacote pendente");
                return false;
            }).isEmpty());
        }
    }

    /** Mantém a reserva bloqueada até o commit, sem fazer outro consumidor esperar por ela. */
    @Test
    void shouldSkipPacketLockedByAnotherProcessingTransaction() throws Exception {
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db)) {
            UUID packetId = enqueue(db, fixture);
            var reserved = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> new PacketProcessingRepository(db).processNext(
                        fixture.tenantId(), Set.of("suntech-ascii-v3"), processing -> {
                            reserved.countDown();
                            try {
                                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Reserva não liberada");
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(error);
                            }
                            processing.markProcessed();
                            return true;
                        }));
                try {
                    assertTrue(reserved.await(5, TimeUnit.SECONDS));
                    var second = executor.submit(() -> new PacketProcessingRepository(db).processNext(
                            fixture.tenantId(), Set.of("suntech-ascii-v3"), processing -> {
                                fail("Pacote já reservado por outro consumidor");
                                return false;
                            }));
                    assertTrue(second.get(5, TimeUnit.SECONDS).isEmpty());
                } finally {
                    release.countDown();
                }
                assertTrue(first.get(5, TimeUnit.SECONDS).orElseThrow());
            }
            assertPacket(db, fixture, packetId, "processed", 1);
        }
    }

    /** Uma exceção do processamento desfaz inclusive os efeitos anteriores ao savepoint. */
    @Test
    void shouldRollbackResponseAndMessageWhenCallbackFails() throws Exception {
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db)) {
            UUID packetId = enqueue(db, fixture);
            var failure = new IllegalStateException("SIMULATED_PROCESSING_FAILURE");
            var actual = assertThrows(IllegalStateException.class,
                    () -> new PacketProcessingRepository(db).processNext(fixture.tenantId(),
                            Set.of("suntech-ascii-v3"), processing -> {
                                processing.saveResponse("RES");
                                assertTrue(processing.saveTelemetry(List.of()));
                                throw failure;
                            }));
            assertSame(failure, actual);
            assertPacket(db, fixture, packetId, "pending", 0);
            assertNoEffects(db, fixture);
        }
    }

    /** O worker deve falhar, sem reportar sucesso, quando o commit não puder ser concluído. */
    @Test
    void shouldRollbackAndPropagateCommitFailure() throws Exception {
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db)) {
            UUID packetId = enqueue(db, fixture);
            DataSource failing = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(db, args);
                            if (method.getName().equals("getConnection")) {
                                Connection connection = (Connection) result;
                                return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                        new Class<?>[]{Connection.class}, (ignored, operation, parameters) -> {
                                            if (operation.getName().equals("commit")) {
                                                throw new SQLException("SIMULATED_COMMIT_FAILURE");
                                            }
                                            try {
                                                return operation.invoke(connection, parameters);
                                            } catch (InvocationTargetException error) {
                                                throw error.getCause();
                                            }
                                        });
                            }
                            return result;
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        }
                    });
            var error = assertThrows(IllegalStateException.class,
                    () -> new TrackingWorker(failing, fixture.tenantId()).processOne());
            assertEquals("SIMULATED_COMMIT_FAILURE", assertInstanceOf(SQLException.class, error.getCause()).getMessage());
            assertPacket(db, fixture, packetId, "pending", 0);
            assertNoEffects(db, fixture);
        }
    }

    /** Insere bytes reais pela ingestão, com hash e escopo derivados do cadastro da fixture. */
    private UUID enqueue(DataSource db, TestFixtures fixture) {
        String identity = fixture.deviceIdentity();
        return new JdbcPacketIngestion(db).accept(new ReceivedFrame("suntech", identity,
                ("ALV;" + identity + "\r").getBytes(StandardCharsets.US_ASCII), "suntech-ascii-v3"),
                fixture.tenantId(), fixture.deviceId());
    }

    /** Confere estado e tentativas sem depender do resultado em memória do repositório. */
    private void assertPacket(DataSource db, TestFixtures fixture, UUID id, String status, int attempts)
            throws SQLException {
        try (var connection = db.getConnection(); var query = connection.prepareStatement("""
                SELECT status, attempts FROM raw_packets WHERE tenant_id=? AND device_id=? AND id=?
                """)) {
            query.setObject(1, fixture.tenantId());
            query.setObject(2, fixture.deviceId());
            query.setObject(3, id);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals(status, row.getString("status"));
                assertEquals(attempts, row.getInt("attempts"));
            }
        }
    }

    /** Confere que nenhuma mensagem ou resposta foi confirmada após uma falha. */
    private void assertNoEffects(DataSource db, TestFixtures fixture) throws SQLException {
        try (var connection = db.getConnection(); var query = connection.prepareStatement("""
                SELECT (SELECT count(*) FROM messages WHERE tenant_id=?),
                       (SELECT count(*) FROM device_command_responses WHERE tenant_id=?)
                """)) {
            query.setObject(1, fixture.tenantId());
            query.setObject(2, fixture.tenantId());
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals(0, row.getInt(1));
                assertEquals(0, row.getInt(2));
            }
        }
    }
}
