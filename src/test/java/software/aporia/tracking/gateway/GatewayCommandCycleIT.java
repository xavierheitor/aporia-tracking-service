package software.aporia.tracking.gateway;

import com.zaxxer.hikari.HikariDataSource;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import software.aporia.tracking.protocol.suntech.FirmwareVersionRequest;
import software.aporia.tracking.storage.command.DeviceCommandRepository;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;
import software.aporia.tracking.worker.WorkerApplication;

import static org.junit.jupiter.api.Assertions.*;

/** Inicia gateway e worker em processos separados para observar descoberta, aprovação, ingestão e envio de comandos pelo socket. */
class GatewayCommandCycleIT {
    /** Diretório temporário que isola os arquivos gerados pelo cenário. */
    @TempDir Path runtime;

    /**
     * * Controla o ciclo de vida de uma JVM iniciada pelo teste.
     * @param process processo filho iniciado para o ensaio
     */
    private record Service(Process process) implements AutoCloseable {
        /**
         * Solicita o encerramento do processo filho e aguarda até o limite do cenário.
         */
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

    /**
     * Inicia gateway ou worker com a conexão do schema isolado e captura seus logs.
     *
     * @param main classe com o ponto de entrada do processo filho
     * @param db fonte de conexões JDBC para a operação
     * @param log arquivo que recebe a saída do processo de teste
     * @return processo filho acompanhado pelo cenário
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
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
    /**
     * Repete a observação até a condição se cumprir ou o prazo expirar.
     *
     * @param description descrição da observação
     * @param predicate condição observada até o prazo do cenário
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @SuppressWarnings("SleepWhileInLoop")
    private void await(String description, Callable<Boolean> predicate) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            if (predicate.call()) return;
            Thread.sleep(25);
        }
        fail(description);
    }

    /**
     * Conta os registros da tabela no tenant do cenário.
     *
     * @param c conexão JDBC usada pelo chamador
     * @param table tabela consultada pelo cenário
     * @param tenant identificador do tenant que delimita a operação
     * @return quantidade de registros no escopo da consulta
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private int count(Connection c, String table, UUID tenant) throws Exception {
        try (var s = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id=?")) {
            s.setObject(1, tenant);
            try (var rows = s.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /**
     * Persiste a consulta de firmware com a chave idempotente da requisição.
     *
     * @param db fonte de conexões JDBC para a operação
     * @param fixture cadastro de teste utilizado no cenário
     * @param tenant identificador do tenant que delimita a operação
     * @param principal identificador do solicitante auditado
     * @param key chave que identifica repetições da mesma solicitação
     * @return UUID da consulta, ou nulo se o dispositivo não for elegível
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private UUID enqueue(HikariDataSource db, TestFixtures fixture, UUID tenant, UUID principal, UUID key)
            throws Exception {
        try (var c = db.getConnection()) {
            c.setAutoCommit(false);
            var id = new DeviceCommandRepository().enqueueFirmwareVersion(c, tenant, fixture.deviceId(), principal, key);
            c.commit();
            return id.orElse(null);
        }
    }

    /**
     * Percorre enfileiramento offline, reconexão, envio e respostas sem correlação pelas entradas
     * reais do serviço.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
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
            // Antecipa o vencimento para simular a espera pela resposta durante a desconexão.
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
    /**
     * Mede três rajadas de 200 posições em sockets persistentes, separadas por um minuto.
     * O cronômetro inclui envio, ingestão e commit da última posição de cada rodada.
     *
     * @throws Exception se o ambiente isolado ou os processos do ensaio falharem
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "TRACKING_TEST_FLEET", matches = "200")
    void processesTwoHundredDevicesEveryMinute() throws Exception {
        final int fleetSize = 200;
        Path log = runtime.resolve("fleet-gateway.log");
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db);
             var c = db.getConnection(); var gateway = start(TcpGateway.class, db, log);
             var worker = start(WorkerApplication.class, db, runtime.resolve("fleet-worker.log"))) {
            var listener = Pattern.compile("Tracking TCP Gateway listening on 127\\.0\\.0\\.1:(\\d+)");
            await("Gateway did not start", () -> listener.matcher(Files.readString(log)).find());
            var match = listener.matcher(Files.readString(log));
            assertTrue(match.find());
            int port = Integer.parseInt(match.group(1));
            var identities = new ArrayList<String>();
            // Uma associação e um ativo distintos garantem que last_positions represente os 200 veículos.
            try (var insert = c.prepareStatement("""
                    WITH fleet AS MATERIALIZED (
                        SELECT gen_random_uuid() AS device, gen_random_uuid() AS asset,
                               '0000000' || lpad(i::text, 3, '0') AS identity, ?::uuid AS tenant
                        FROM generate_series(1, ?) i
                    ), devices_added AS (
                        INSERT INTO devices(id, tenant_id, protocol, identity)
                        SELECT device, tenant, 'suntech', identity FROM fleet RETURNING id
                    ), assets_added AS (
                        INSERT INTO assets(id, tenant_id, name)
                        SELECT asset, tenant, identity FROM fleet RETURNING id
                    ), assigned AS (
                        INSERT INTO assignments(id, tenant_id, device_id, asset_id, starts_at)
                        SELECT gen_random_uuid(), tenant, device, asset, '2026-01-01 UTC'::timestamptz
                        FROM fleet JOIN devices_added d ON d.id=device JOIN assets_added a ON a.id=asset
                    ) SELECT identity FROM fleet ORDER BY identity
                    """)) {
                insert.setObject(1, fixture.tenantId());
                insert.setInt(2, fleetSize);
                try (var rows = insert.executeQuery()) {
                    while (rows.next()) identities.add(rows.getString(1));
                }
            }
            assertEquals(fleetSize, identities.size());
            List<Socket> sockets = new ArrayList<>();
            try {
                for (int i = 0; i < fleetSize; i++) sockets.add(new Socket("127.0.0.1", port));
                long first = System.nanoTime();
                for (int round = 0; round < 3; round++) {
                    TimeUnit.NANOSECONDS.sleep(first + TimeUnit.MINUTES.toNanos(round) - System.nanoTime());
                    long start = System.nanoTime();
                    String time = String.format(Locale.ROOT, "08:%02d:00", round);
                    for (int i = 0; i < fleetSize; i++) {
                        String frame = "STT;" + identities.get(i) + ";013831;20261120;" + time
                                + ";-16.6700;-49.2500;45.5;1\r";
                        sockets.get(i).getOutputStream().write(frame.getBytes(StandardCharsets.US_ASCII));
                    }
                    int expected = fleetSize * (round + 1);
                    while (count(c, "positions", fixture.tenantId()) < expected) {
                        assertTrue(gateway.process().isAlive() && worker.process().isAlive());
                        assertTrue(System.nanoTime() - start < TimeUnit.MINUTES.toNanos(1),
                                "Worker did not drain the burst within a minute");
                        TimeUnit.MILLISECONDS.sleep(100);
                    }
                    long elapsed = System.nanoTime() - start;
                    assertTrue(elapsed < TimeUnit.MINUTES.toNanos(1));
                    assertEquals(expected, count(c, "raw_packets", fixture.tenantId()));
                    assertEquals(expected, count(c, "positions", fixture.tenantId()));
                    assertEquals(expected, count(c, "outbox_events", fixture.tenantId()));
                    assertEquals(fleetSize, count(c, "last_positions", fixture.tenantId()));
                    try (var check = c.prepareStatement("""
                            SELECT count(*) FROM raw_packets
                            WHERE tenant_id=? AND (status <> 'processed' OR attempts <> 1)
                            """)) {
                        check.setObject(1, fixture.tenantId());
                        try (var row = check.executeQuery()) {
                            row.next();
                            assertEquals(0, row.getInt(1));
                        }
                    }
                    System.out.printf(Locale.ROOT, "Frota 200: rodada %d, %.2f s, %d posições acumuladas%n",
                            round + 1, elapsed / 1_000_000_000.0, expected);
                }
            } finally {
                for (Socket socket : sockets) socket.close();
            }
        }
    }

    /**
     * Exercita descoberta AVL, aprovação, ingestão, retransmissão e desabilitação na mesma porta
     * TCP.
     *
     * @param port porta TCP atribuída ao gateway de teste
     * @param c conexão JDBC usada pelo chamador
     * @param fixture cadastro de teste utilizado no cenário
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private void verifyAvl(int port, Connection c, TestFixtures fixture) throws Exception {
        String imei = "35000" + fixture.deviceIdentity();
        var hex = java.util.HexFormat.of();
        byte[] login = java.nio.ByteBuffer.allocate(17).putShort((short)15)
                .put(imei.getBytes(StandardCharsets.US_ASCII)).array();
        // Cada lote traz duas posições nos hemisférios sul e oeste. O lote estendido também inclui um campo de tamanho variável.
        byte[] codec8 = hex.parseHex("00000000000000430802000001a3185c500000e3fc5e80f268ff00007b001f070019000101ef01000000000001a3185c500100e3fc5e80f268ff00007b001f070019000101ef010000000200005a0c");
        byte[] extended = hex.parseHex("00000000000000638e02000001a3185c500000e3fc5e80f268ff00007b001f07001900000002000100ef01000000000000000101000003616263000001a3185c500100e3fc5e80f268ff00007b001f07001900000002000100ef01000000000000000101000003616263020000dd1a");
        UUID approved;
        byte[] invalid = codec8.clone();
        invalid[invalid.length - 1] ^= 1;
        for (byte[] batch : new byte[][]{invalid, codec8, extended}) {
            try (var pending = new Socket("127.0.0.1", port)) {
                pending.setSoTimeout(5000);
                pending.getOutputStream().write(login);
                assertEquals(1, pending.getInputStream().read());
                pending.getOutputStream().write(batch);
                assertEquals(-1, pending.getInputStream().read());
            }
        }
        assertEquals(0, count(c, "raw_packets", fixture.tenantId()));
        try (var query = c.prepareStatement("""
                SELECT id, manufacturer, device_type, wire_format, reported_model, observations
                FROM pending_device_discoveries WHERE protocol='teltonika' AND identity=?
                """)) {
            query.setString(1, imei);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                approved = row.getObject(1, UUID.class);
                assertEquals("Teltonika", row.getString(2));
                assertEquals("gps_tracker", row.getString(3));
                assertEquals("Codec 8E", row.getString(4));
                assertNull(row.getString(5));
                assertEquals(2, row.getInt(6));
                assertFalse(row.next());
            }
        }
        try (var approve = c.prepareStatement("SELECT approve_device_discovery(?,?)")) {
            approve.setObject(1, approved);
            approve.setObject(2, fixture.tenantId());
            for (int attempt = 0; attempt < 2; attempt++) {
                try (var row = approve.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals(approved, row.getObject(1, UUID.class));
                }
            }
            approve.setObject(2, UUID.randomUUID());
            var conflict = assertThrows(java.sql.SQLException.class, approve::executeQuery);
            assertTrue(conflict.getMessage().contains("DISCOVERY_ALREADY_APPROVED"));
        }
        try (var update = c.prepareStatement("UPDATE assignments SET device_id=? WHERE tenant_id=? AND device_id=?")) {
            update.setObject(1, approved);
            update.setObject(2, fixture.tenantId());
            update.setObject(3, fixture.deviceId());
            assertEquals(1, update.executeUpdate());
        }
        try (var tracker = new Socket("127.0.0.1", port)) {
            tracker.setSoTimeout(5000);
            tracker.getOutputStream().write(login);
            assertEquals(1, tracker.getInputStream().read());
            tracker.getOutputStream().write(0xff);
            for (byte[] bytes : new byte[][]{codec8, extended, extended}) {
                tracker.getOutputStream().write(bytes);
                assertArrayEquals(new byte[]{0,0,0,2}, tracker.getInputStream().readNBytes(4));
            }
            // Desabilitar o cadastro após o login deve impedir a confirmação do lote seguinte.
            try (var disable = c.prepareStatement("UPDATE devices SET enabled=false WHERE tenant_id=? AND id=?")) {
                disable.setObject(1, fixture.tenantId());
                disable.setObject(2, approved);
                assertEquals(1, disable.executeUpdate());
            }
            tracker.getOutputStream().write(codec8);
            assertEquals(-1, tracker.getInputStream().read());
        }
        try (var disabled = new Socket("127.0.0.1", port)) {
            disabled.setSoTimeout(5000);
            disabled.getOutputStream().write(login);
            assertEquals(0, disabled.getInputStream().read());
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
