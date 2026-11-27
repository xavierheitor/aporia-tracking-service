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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import software.aporia.tracking.core.ReceivedFrame;
import software.aporia.tracking.observability.metrics.WorkerMetrics;
import software.aporia.tracking.protocol.DecoderRegistry;
import software.aporia.tracking.protocol.suntech.SuntechDecoder;
import software.aporia.tracking.protocol.teltonika.TeltonikaAvl;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;
import software.aporia.tracking.worker.TrackingWorker;

/**
 * Exercita o escopo da reserva e as garantias transacionais extraídas do worker.
 *
 * <p>O repositório reserva um único {@code raw_packet} com {@code FOR UPDATE SKIP LOCKED} e só
 * confirma depois do callback. Resposta e telemetria usam a mesma conexão, com savepoint para
 * desfazer apenas a telemetria; uma exceção do callback desfaz a transação inteira. Cada cenário
 * cria o próprio tenant em {@link TestFixtures}, que também remove os registros ao final.</p>
 */
class PacketProcessingRepositoryIT {

    /** Registro em memória de onde as métricas do worker são lidas. */
    private SimpleMeterRegistry meterRegistry;
    /** Métricas observadas após a falha simulada de commit. */
    private WorkerMetrics metrics;
    /** Decoders reais usados pelo worker nos cenários. */
    private DecoderRegistry decoderRegistry;

    /**
     * Cenário: cada teste precisa de métricas isoladas e do registro real de decoders. Ação: cria o
     * registry em memória, as métricas do worker e os decoders Suntech e Teltonika. Resultado: o
     * worker do cenário consegue interpretar os quadros Suntech da fixture.
     */
    @BeforeEach
    void setUpWorkerDependencies() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new WorkerMetrics(meterRegistry);
        decoderRegistry = new DecoderRegistry(
                List.of(
                        new SuntechDecoder(),
                        new TeltonikaAvl()));
    }

    /** Fecha o registry em memória para não acumular medidores entre testes. */
    @AfterEach
    void closeMeterRegistry() {
        meterRegistry.close();
    }

    /**
     * Cenário: um pacote pendente para cada um de dois tenants. Ação: processa apenas o tenant
     * selecionado. Resultado: o pacote desse tenant fica {@code processed} com tentativa 1; o do
     * outro continua {@code pending} com tentativa 0; uma segunda chamada devolve vazio.
     *
     * @throws Exception se a preparação ou a verificação falhar
     */
    @Test
    void shouldOnlyProcessSelectedTenant() throws Exception {
        try (
                var db = TestDatabase.createDataSource();
                var first = new TestFixtures(db);
                var second = new TestFixtures(db)
        ) {
            UUID firstPacket = enqueue(db, first);
            UUID secondPacket = enqueue(db, second);
            var repository = new PacketProcessingRepository(db);
            // O filtro de tenant é aplicado na própria reserva, antes de qualquer callback.
            assertEquals(
                    secondPacket,
                    repository.processNext(second.tenantId(), Set.of("suntech-ascii-v3"), processing -> {
                        assertEquals(second.tenantId(), processing.packet().tenantId());
                        assertEquals(second.deviceId(), processing.packet().deviceId());
                        processing.markProcessed();
                        return processing.packet().id();
                    }).orElseThrow());
            assertPacket(db, first, firstPacket, "pending", 0);
            assertPacket(db, second, secondPacket, "processed", 1);
            // Só depois do commit o estado fica visível para a leitura de verificação.
            assertTrue(repository.processNext(second.tenantId(), Set.of("suntech-ascii-v3"), _ -> {
                fail("O tenant não tem outro pacote pendente");
                return false;
            }).isEmpty());
        }
    }

    /**
     * Cenário: um consumidor reserva o pacote e permanece no callback segurando a transação. Ação:
     * um segundo consumidor tenta processar o mesmo tenant. Resultado: o segundo devolve vazio sem
     * bloquear, pela cláusula {@code SKIP LOCKED}; após a liberação, o primeiro confirma e o pacote
     * fica {@code processed} com tentativa 1.
     *
     * @throws Exception se a preparação ou a verificação falhar
     */
    @Test
    void shouldSkipPacketLockedByAnotherProcessingTransaction() throws Exception {
        try (
                var db = TestDatabase.createDataSource();
                var fixture = new TestFixtures(db)
        ) {
            UUID packetId = enqueue(db, fixture);
            var reserved = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                // A latch garante que a primeira transação já detém o lock antes da segunda tentativa.
                var first = executor.submit(
                        () -> new PacketProcessingRepository(db).processNext(
                                fixture.tenantId(),
                                Set.of("suntech-ascii-v3"),
                                processing -> {
                                    reserved.countDown();
                                    try {
                                        if (!release.await(10, TimeUnit.SECONDS))
                                            throw new AssertionError("Reserva não liberada");
                                    } catch (InterruptedException error) {
                                        Thread.currentThread().interrupt();
                                        throw new AssertionError(error);
                                    }
                                    processing.markProcessed();
                                    return true;
                                }));
                try {
                    assertTrue(reserved.await(5, TimeUnit.SECONDS));
                    // A segunda chamada não espera: a linha bloqueada é ignorada pela reserva.
                    var second = executor.submit(
                            () -> new PacketProcessingRepository(db).processNext(
                                    fixture.tenantId(),
                                    Set.of("suntech-ascii-v3"),
                                    _ -> {
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

    /**
     * Cenário: callback que grava resposta e telemetria e então lança exceção. Ação: processa o
     * pacote. Resultado: a exceção original é propagada, o pacote volta a {@code pending} com
     * tentativa 0 e não restam mensagens nem respostas.
     *
     * @throws Exception se a preparação ou a verificação falhar
     */
    @Test
    void shouldRollbackResponseAndMessageWhenCallbackFails() throws Exception {
        try (
                var db = TestDatabase.createDataSource();
                var fixture = new TestFixtures(db)
        ) {
            UUID packetId = enqueue(db, fixture);
            var failure = new IllegalStateException("SIMULATED_PROCESSING_FAILURE");
            var actual = assertThrows(
                    IllegalStateException.class,
                    () -> new PacketProcessingRepository(db).processNext(
                            fixture.tenantId(),
                            Set.of("suntech-ascii-v3"),
                            processing -> {
                                // A resposta é gravada antes do savepoint; a falha desfaz os dois efeitos.
                                processing.saveResponse("RES");
                                assertTrue(processing.saveTelemetry(List.of()));
                                throw failure;
                            }));
            assertSame(failure, actual);
            assertPacket(db, fixture, packetId, "pending", 0);
            // Confirma que o rollback alcançou resposta e mensagem, não apenas o status do pacote.
            assertNoEffects(db, fixture);
        }
    }

    /**
     * Cenário: worker real com uma conexão cujo {@code commit} falha. Ação: processa um pacote.
     * Resultado: {@link IllegalStateException} com a causa SQL preservada; as métricas registram um
     * ciclo falho, nenhum pacote processado e nenhuma duração; o pacote permanece {@code pending}
     * sem efeitos.
     *
     * @throws Exception se a preparação ou a verificação falhar
     */
    @Test
    void shouldRollbackAndPropagateCommitFailure() throws Exception {
        try (
                var db = TestDatabase.createDataSource();
                var fixture = new TestFixtures(db)
        ) {
            UUID packetId = enqueue(db, fixture);
            // O proxy falha apenas no commit; as demais operações seguem para o pool real.
            DataSource failing = (DataSource) Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(),
                    new Class<?>[] { DataSource.class },
                    (_, method, args) -> {
                        try {
                            Object result = method.invoke(db, args);
                            if (method.getName().equals("getConnection")) {
                                Connection connection = (Connection) result;
                                return Proxy.newProxyInstance(
                                        Connection.class.getClassLoader(),
                                        new Class<?>[] { Connection.class },
                                        (_, operation, parameters) -> {
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
            var error = assertThrows(
                    IllegalStateException.class,
                    () -> new TrackingWorker(failing, fixture.tenantId(), decoderRegistry, metrics).processOne());
            // O worker conta a falha e não publica processamento nem duração.
            assertEquals(1.0, meterRegistry.get("tracking.worker.cycles.failed").counter().count());
            assertEquals(0.0, meterRegistry.get("tracking.packets.processed").counter().count());
            assertEquals(0L, meterRegistry.get("tracking.packet.processing").timer().count());
            assertEquals(
                    "SIMULATED_COMMIT_FAILURE",
                    assertInstanceOf(SQLException.class, error.getCause()).getMessage());
            // A causa SQL foi preservada e o rollback manteve pacote e efeitos como estavam.
            assertPacket(db, fixture, packetId, "pending", 0);
            assertNoEffects(db, fixture);
        }
    }

    /**
     * Cenário: fixture com dispositivo habilitado. Ação: ingere um quadro ALV real pela ingestão de
     * produção. Resultado: devolve o UUID do pacote {@code pending}, com hash e escopo derivados do
     * cadastro.
     *
     * @param db pool do schema isolado
     * @param fixture cadastro que define tenant, dispositivo e identidade
     * @return UUID do pacote ingerido
     */
    private UUID enqueue(DataSource db, TestFixtures fixture) {
        String identity = fixture.deviceIdentity();
        return new JdbcPacketIngestion(db).accept(
                new ReceivedFrame(
                        "suntech",
                        identity,
                        ("ALV;" + identity + "\r").getBytes(StandardCharsets.US_ASCII),
                        "suntech-ascii-v3"),
                fixture.tenantId(),
                fixture.deviceId());
    }

    /**
     * Cenário: verificação do estado após o processamento. Ação: consulta status e tentativas pelo
     * tenant, dispositivo e id. Resultado: confere os valores esperados sem depender do resultado em
     * memória do repositório.
     *
     * @param db pool do schema isolado
     * @param fixture cadastro que delimita o escopo
     * @param id identificador da recepção
     * @param status status esperado
     * @param attempts tentativas esperadas
     * @throws SQLException se a operação JDBC falhar
     */
    private void assertPacket(DataSource db, TestFixtures fixture, UUID id, String status, int attempts)
            throws SQLException {
        try (
                var connection = db.getConnection();
                var query = connection.prepareStatement("""
                        SELECT status, attempts FROM raw_packets WHERE tenant_id=? AND device_id=? AND id=?
                        """)
        ) {
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

    /**
     * Cenário: verificação de que a falha não confirmou efeitos. Ação: conta mensagens e respostas
     * do tenant. Resultado: as duas contagens são zero.
     *
     * @param db pool do schema isolado
     * @param fixture cadastro que delimita o escopo
     * @throws SQLException se a operação JDBC falhar
     */
    private void assertNoEffects(DataSource db, TestFixtures fixture) throws SQLException {
        try (
                var connection = db.getConnection();
                var query = connection.prepareStatement("""
                        SELECT (SELECT count(*) FROM messages WHERE tenant_id=?),
                               (SELECT count(*) FROM device_command_responses WHERE tenant_id=?)
                        """)
        ) {
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
