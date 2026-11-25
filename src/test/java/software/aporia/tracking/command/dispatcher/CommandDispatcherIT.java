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
import software.aporia.tracking.storage.device.JdbcDeviceLookup;
import software.aporia.tracking.storage.device.RegisteredDevice;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;
import software.aporia.tracking.support.CommandTestFixtures;

import static org.junit.jupiter.api.Assertions.*;
import static software.aporia.tracking.command.dispatcher.CommandDispatcher.DispatchResult.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.aporia.tracking.observability.metrics.CommandMetrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

/** Exercita a reserva e o resultado persistido da transmissão, inclusive quando a gravação da conclusão falha. */
class CommandDispatcherIT {

    private SimpleMeterRegistry meterRegistry;
    private CommandMetrics commandMetrics;

    /** Diretório temporário que isola os arquivos gerados pelo cenário. */
    @TempDir
    Path directory;

    @BeforeEach
    void setupMetrics() {
        meterRegistry = new SimpleMeterRegistry();
        commandMetrics = new CommandMetrics(meterRegistry);
    }

    @AfterEach
    void closeMetrics() {
        meterRegistry.close();
    }

    /**
     * Representa a escrita controlada pelo cenário para reproduzir sucesso, interrupção ou falha.
     */
    @FunctionalInterface
    private interface Writer {
        /**
         * Permite ao cenário decidir quantos bytes chegam ao destino antes de uma falha.
         *
         * @param bytes sequência de bytes usada na escrita
         * @throws IOException se a operação de entrada ou saída falhar
         */
        void send(byte[] bytes) throws IOException;
    }

    /**
     * Monta o dispatcher com banco real e um canal cujo comportamento o teste controla.
     *
     * @param db     fonte de conexões JDBC para a operação
     * @param owner  proprietário do envio ou seu identificador persistido
     * @param device cadastro de teste utilizado no cenário
     * @param writer comportamento de escrita simulado na sessão
     * @return dispatcher conectado ao canal controlado pelo cenário
     */
    private CommandDispatcher dispatcher(DataSource db, DispatchOwner owner, TestFixtures device, Writer writer) {
        UUID token = UUID.randomUUID();
        activate(db, device, token);
        return dispatcher(db, owner, device, token, writer);
    }

    private void activate(DataSource db, TestFixtures device, UUID token) {
        new JdbcDeviceLookup(db).activateSession(
                new RegisteredDevice(
                        device.tenantId(),
                        device.deviceId(),
                        "suntech",
                        device.deviceIdentity()),
                token);
    }

    private CommandDispatcher dispatcher(
            DataSource db,
            DispatchOwner owner,
            TestFixtures device,
            UUID token,
            Writer writer) {
        var endpoint = new CommandTransport.Endpoint() {
            @Override
            public UUID sessionToken() {
                return token;
            }

            /**
             * Informa o protocolo da sessão simulada.
             *
             * @return identificador do protocolo desta implementação
             */
            @Override
            public String protocol() {
                return "suntech";
            }

            /**
             * Associa a sessão simulada à identidade da fixture.
             *
             * @return identidade do equipamento no protocolo
             */
            @Override
            public String identity() {
                return device.deviceIdentity();
            }

            /**
             * Delega a escrita ao comportamento escolhido pelo cenário.
             *
             * @param bytes sequência de bytes usada na escrita
             * @throws IOException se a operação de entrada ou saída falhar
             */
            @Override
            public void send(byte[] bytes) throws IOException {
                writer.send(bytes);
            }
        };
        return new CommandDispatcher(
                db,
                new DeviceCommandDispatchRepository(),
                (tenant, id) -> device.tenantId().equals(tenant) && device.deviceId().equals(id)
                        ? Optional.of(endpoint)
                        : Optional.empty(),
                owner,
                commandMetrics);
    }

    /**
     * Insere um comando legado para exercitar a política de envio.
     *
     * @param db      fonte de conexões JDBC para a operação
     * @param device  cadastro de teste utilizado no cenário
     * @param payload bytes do conteúdo da mensagem
     * @return UUID do comando inserido
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private UUID enqueue(DataSource db, TestFixtures device, byte[] payload) throws Exception {
        try (var c = db.getConnection()) {
            return CommandTestFixtures.enqueue(
                    c,
                    device.tenantId(),
                    device.deviceId(),
                    "suntech",
                    payload,
                    Instant.now().plusSeconds(300));
        }
    }

    /**
     * Enfileira a consulta de firmware auditada dentro de uma transação.
     *
     * @param db     fonte de conexões JDBC para a operação
     * @param device cadastro de teste utilizado no cenário
     * @return UUID da consulta de firmware
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private UUID requestFirmware(DataSource db, TestFixtures device) throws Exception {
        try (var c = db.getConnection()) {
            c.setAutoCommit(false);
            UUID id = new DeviceCommandRepository().enqueueFirmwareVersion(
                    c,
                    device.tenantId(),
                    device.deviceId(),
                    UUID.randomUUID(),
                    UUID.randomUUID()).orElseThrow();
            c.commit();
            return id;
        }
    }

    /**
     * Consulta o resultado persistido no escopo do tenant.
     *
     * @param db     fonte de conexões JDBC para a operação
     * @param device cadastro de teste utilizado no cenário
     * @param id     identificador do registro consultado
     * @return estado do comando consultado no banco de testes
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private String status(DataSource db, TestFixtures device, UUID id) throws Exception {
        try (var c = db.getConnection()) {
            return CommandTestFixtures.findStatus(c, device.tenantId(), id).orElseThrow();
        }
    }

    /**
     * Simula escrita parcial e distingue a entrega incerta da rejeição anterior ao envio.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void persistsPartialWriteAndRejectsUnapprovedAndWrongIdentity() throws Exception {
        try (
                var db = TestDatabase.createDataSource();
                var fixture = new TestFixtures(db);
                var owner = DispatchOwner.open(directory.resolve("gateway"))
        ) {
            var prefix = new ByteArrayOutputStream();
            var dispatcher = dispatcher(db, owner, fixture, bytes -> {
                prefix.write(bytes, 0, 3);
                throw new IOException("simulated connection loss");
            });
            byte[] valid = FirmwareVersionRequest.encode(fixture.deviceIdentity());
            byte[] programming = ("PRG;" + fixture.deviceIdentity() + ";16;70#60\r")
                    .getBytes(StandardCharsets.US_ASCII);
            for (byte[] payload : new byte[][] { programming, FirmwareVersionRequest.encode("0000000001"), valid }) {
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
            assertEquals(3.0, meterRegistry.get("tracking.command.dispatch")
                    .tag("result", "delivery_failed").counter().count());
            assertEquals(1.0, meterRegistry.get("tracking.command.dispatch")
                    .tag("result", "delivery_unknown").counter().count());
            assertEquals(1.0, meterRegistry.get("tracking.command.tcp.write.errors").counter().count());
            assertEquals(1L, meterRegistry.get("tracking.command.tcp.write").timer().count());
        }
    }

    /**
     * Provoca falha no banco após a escrita e verifica que o erro continua visível sem retransmissão.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void databaseFailureAfterWriteIsVisibleAndNeverReplays() throws Exception {
        try (
                var db = TestDatabase.createDataSource();
                var fixture = new TestFixtures(db);
                var owner = DispatchOwner.open(directory.resolve("gateway"));
                var c = db.getConnection()
        ) {
            UUID command = requestFirmware(db, fixture);
            // O gatilho afeta somente a conclusão deste comando no schema isolado do teste.
            try (var sql = c.createStatement()) {
                sql.execute(
                        "ALTER TABLE device_commands ADD CONSTRAINT it_fail_mark_sent "
                                + "CHECK (id <> '" + command + "'::uuid OR status <> 'sent')");
                try {
                    var bytes = new ByteArrayOutputStream();
                    var dispatcher = dispatcher(db, owner, fixture, bytes::writeBytes);
                    var failure = assertThrows(
                            java.sql.SQLException.class,
                            () -> dispatcher.dispatchNext(fixture.tenantId(), fixture.deviceId()));
                    assertEquals("23514", failure.getSQLState());
                    assertEquals(1.0, meterRegistry.get("tracking.command.dispatch.errors").counter().count());
                    assertEquals(0.0, meterRegistry.get("tracking.command.dispatch")
                            .tag("result", "sent").counter().count());
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

    /**
     * Pausa o emissor durante a escrita, exercita a exclusão entre proprietários e recupera a tentativa após reinício.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void anotherOwnerCannotRevokePausedWriterAndRestartRecoversCrashAfterWrite() throws Exception {
        Path ownerFile = directory.resolve("gateway");
        try (
                var db = TestDatabase.createDataSource();
                var fixture = new TestFixtures(db);
                var other = DispatchOwner.open(directory.resolve("other"))
        ) {
            byte[] valid = FirmwareVersionRequest.encode(fixture.deviceIdentity());
            UUID command = requestFirmware(db, fixture);
            var bytes = new ByteArrayOutputStream();
            try (
                    var owner = DispatchOwner.open(ownerFile);
                    var executor = Executors.newVirtualThreadPerTaskExecutor()
            ) {
                var unavailable = assertThrows(IOException.class, () -> DispatchOwner.open(ownerFile));
                assertEquals("DISPATCH_OWNER_UNAVAILABLE", unavailable.getMessage());
                var authorized = new CountDownLatch(1);
                var resume = new CountDownLatch(1);
                var dispatcher = dispatcher(db, owner, fixture, payload -> {
                    authorized.countDown();
                    try {
                        if (!resume.await(5, TimeUnit.SECONDS))
                            throw new IOException("test timed out");
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                    bytes.writeBytes(payload);
                    throw new IllegalStateException("simulated process death after write");
                });
                var run = executor
                        .submit(() -> dispatcher.dispatchNext(fixture.tenantId(), fixture.deviceId()));
                try {
                    assertTrue(authorized.await(5, TimeUnit.SECONDS));
                    try (
                            var c = db.getConnection();
                            var s = c.prepareStatement("""
                                    UPDATE device_commands SET transmission_started_at=now()-interval '1 day'
                                    WHERE tenant_id=? AND id=? AND status='transmitting'
                                    """)
                    ) {
                        s.setObject(1, fixture.tenantId());
                        s.setObject(2, command);
                        assertEquals(1, s.executeUpdate());
                    }
                    UUID newToken = UUID.randomUUID();
                    activate(db, fixture, newToken);
                    var second = dispatcher(db, other, fixture, newToken, payload -> fail("second writer"));
                    second.recover();
                    assertEquals("transmitting", status(db, fixture, command));
                    assertEquals(NO_COMMAND, second.dispatchNext(fixture.tenantId(), fixture.deviceId()));
                    assertEquals(0, bytes.size());
                } finally {
                    resume.countDown();
                }
                var crash = assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> run.get(5, TimeUnit.SECONDS));
                var cause = assertInstanceOf(IllegalStateException.class, crash.getCause());
                assertEquals("simulated process death after write", cause.getMessage());
                assertArrayEquals(valid, bytes.toByteArray());
            }
            try (var restarted = DispatchOwner.open(ownerFile)) {
                var dispatcher = dispatcher(
                        db,
                        restarted,
                        fixture,
                        UUID.randomUUID(),
                        payload -> fail("unexpected replay"));
                dispatcher.recover();
                assertEquals("delivery_unknown", status(db, fixture, command));
                assertEquals(NO_COMMAND, dispatcher.dispatchNext(fixture.tenantId(), fixture.deviceId()));
                activate(db, fixture, UUID.randomUUID());
            }
        }
    }

    /** Três gateways ainda enxergam sockets locais, mas só o último login pode consumir a fila. */
    @Test
    void onlyNewestOfThreeSessionsCanClaim() throws Exception {
        try (
                var db = TestDatabase.createDataSource();
                var device = new TestFixtures(db);
                var a = DispatchOwner.open(directory.resolve("a"));
                var b = DispatchOwner.open(directory.resolve("b"));
                var c = DispatchOwner.open(directory.resolve("c"))
        ) {
            var first = dispatcher(db, a, device, bytes -> fail("sessão antiga"));
            var second = dispatcher(db, b, device, bytes -> fail("sessão substituída"));
            var output = new ByteArrayOutputStream();
            var third = dispatcher(db, c, device, output::writeBytes);
            UUID id = requestFirmware(db, device);
            assertEquals(NO_COMMAND, first.dispatchNext(device.tenantId(), device.deviceId()));
            assertEquals(NO_COMMAND, second.dispatchNext(device.tenantId(), device.deviceId()));
            assertEquals("queued", status(db, device, id));
            assertEquals(SENT, third.dispatchNext(device.tenantId(), device.deviceId()));
            assertEquals(1.0, meterRegistry.get("tracking.command.dispatch")
                    .tag("result", "sent").counter().count());
            assertEquals(1L, meterRegistry.get("tracking.command.dispatch.duration")
                    .tag("result", "sent").timer().count());
            assertArrayEquals(FirmwareVersionRequest.encode(device.deviceIdentity()), output.toByteArray());
        }
    }
}
