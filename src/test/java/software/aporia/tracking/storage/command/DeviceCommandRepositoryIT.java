package software.aporia.tracking.storage.command;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.protocol.suntech.FirmwareVersionRequest;
import software.aporia.tracking.support.CommandTestFixtures;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

/**
 * Exercita a entrada auditada de produção, seus limites e o isolamento entre tenants.
 *
 * <p>O schema é isolado por JVM e cada cenário cria um tenant próprio com {@link TestFixtures}.
 * A conexão do teste controla a transação: a entrada de produção exige autocommit desligado, e
 * os registros criados são removidos pelo fechamento da fixture.</p>
 */
class DeviceCommandRepositoryIT {
    /**
     * Repositório de produção exercitado; o teste injeta apenas a conexão transacional.
     */
    private final DeviceCommandRepository repository = new DeviceCommandRepository();

    /**
     * Cenário: equipamento habilitado do tenant da fixture. Ação: enfileira a consulta de firmware
     * auditada e confirma a transação. Resultado: o comando fica em {@code queued}, com protocolo
     * Suntech, payload do cadastro, solicitante e chave gravados, nenhuma tentativa e prazo de
     * aproximadamente 120 segundos.
     *
     * @throws Exception se a preparação ou a verificação falhar
     */
    @Test
    void shouldEnqueueAuditedFirmwareRequest() throws Exception {
        try (var db = TestDatabase.createDataSource();
                var fixture = new TestFixtures(db);
                var connection = db.getConnection()) {
            connection.setAutoCommit(false);
            UUID principal = UUID.randomUUID();
            UUID key = UUID.randomUUID();
            Instant before = Instant.now();
            UUID id = repository.enqueueFirmwareVersion(connection, fixture.tenantId(), fixture.deviceId(),
                    principal, key).orElseThrow();
            connection.commit();
            // Lê o contrato persistido no escopo do tenant, sem depender do UUID devolvido.
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
                    // A tolerância cobre a latência entre o relógio do teste e o NOW() do banco.
                    long delay = Duration.between(before, row.getObject("expires_at", OffsetDateTime.class).toInstant())
                            .toSeconds();
                    assertTrue(delay >= 119 && delay <= 122);
                }
            }
        }
    }

    /**
     * Cenário: equipamento desabilitado na própria transação do teste. Ação: tenta enfileirar a
     * consulta. Resultado: a operação devolve vazio porque o SELECT de elegibilidade não encontra o
     * dispositivo; o rollback descarta o UPDATE e o cenário não deixa estado.
     *
     * @throws Exception se a preparação ou a verificação falhar
     */
    @Test
    void shouldRejectDisabledDevice() throws Exception {
        try (var db = TestDatabase.createDataSource();
                var fixture = new TestFixtures(db);
                var connection = db.getConnection()) {
            connection.setAutoCommit(false);
            // A desabilitação ainda não foi confirmada: a produção a enxerga e o rollback a descarta.
            try (var update = connection
                    .prepareStatement("UPDATE devices SET enabled=false WHERE tenant_id=? AND id=?")) {
                update.setObject(1, fixture.tenantId());
                update.setObject(2, fixture.deviceId());
                assertEquals(1, update.executeUpdate());
            }
            assertTrue(repository.enqueueFirmwareVersion(connection, fixture.tenantId(), fixture.deviceId(),
                    UUID.randomUUID(), UUID.randomUUID()).isEmpty());
            connection.rollback();
        }
    }

    /**
     * Cenário: um tenant sem cadastro e o tenant proprietário do equipamento. Ação: tenta enfileirar
     * com o tenant aleatório e depois com o proprietário. Resultado: a primeira chamada devolve
     * vazio; a segunda grava o comando em {@code queued}, comprovando o escopo por tenant.
     *
     * @throws Exception se a preparação ou a verificação falhar
     */
    @Test
    void shouldRejectCrossTenantRequest() throws Exception {
        try (var db = TestDatabase.createDataSource();
                var fixture = new TestFixtures(db);
                var connection = db.getConnection()) {
            connection.setAutoCommit(false);
            // O tenant aleatório não tem vínculo com o dispositivo; a busca falha sem gravar nada.
            assertTrue(repository.enqueueFirmwareVersion(connection, UUID.randomUUID(), fixture.deviceId(),
                    UUID.randomUUID(), UUID.randomUUID()).isEmpty());
            UUID id = repository.enqueueFirmwareVersion(connection, fixture.tenantId(), fixture.deviceId(),
                    UUID.randomUUID(), UUID.randomUUID()).orElseThrow();
            connection.commit();
            assertEquals("queued", CommandTestFixtures.findStatus(connection, fixture.tenantId(), id).orElseThrow());
        }
    }

    /**
     * Cenário: fila já com dez consultas pendentes para o mesmo equipamento. Ação: repete a
     * requisição original e, em seguida, tenta uma chave nova. Resultado: a repetição devolve o
     * mesmo UUID sem ocupar vaga; a chave nova lança
     * {@link DeviceCommandRepository.QueueFullException}.
     *
     * @throws Exception se a preparação ou a verificação falhar
     */
    @Test
    void shouldPreserveIdempotencyWhenQueueIsFull() throws Exception {
        try (var db = TestDatabase.createDataSource();
                var fixture = new TestFixtures(db);
                var connection = db.getConnection()) {
            connection.setAutoCommit(false);
            UUID principal = UUID.randomUUID();
            UUID key = UUID.randomUUID();
            UUID first = repository
                    .enqueueFirmwareVersion(connection, fixture.tenantId(), fixture.deviceId(), principal, key)
                    .orElseThrow();
            // Completa o limite de dez comandos pendentes com chaves distintas.
            for (int i = 1; i < 10; i++) {
                repository.enqueueFirmwareVersion(connection, fixture.tenantId(), fixture.deviceId(), principal,
                        UUID.randomUUID()).orElseThrow();
            }
            connection.commit();
            // A verificação de idempotência vem antes do limite, então a repetição não conta como nova vaga.
            assertEquals(first,
                    repository
                            .enqueueFirmwareVersion(connection, fixture.tenantId(), fixture.deviceId(), principal, key)
                            .orElseThrow());
            assertThrows(DeviceCommandRepository.QueueFullException.class, () -> repository.enqueueFirmwareVersion(
                    connection, fixture.tenantId(), fixture.deviceId(), principal, UUID.randomUUID()));
            // Desfaz a tentativa recusada; o lote confirmado fica para a limpeza da fixture.
            connection.rollback();
        }
    }

    /**
     * Cenário: conexão em autocommit e, depois, transação aberta. Ação: chama a entrada pública sem
     * transação e com auditoria nula. Resultado: a primeira chamada falha com
     * {@code ENQUEUE_REQUIRES_TRANSACTION}; as seguintes falham com {@link NullPointerException}
     * antes de qualquer gravação. O rollback encerra a transação.
     *
     * @throws Exception se a preparação ou a verificação falhar
     */
    @Test
    void shouldRequireTransactionAndAudit() throws Exception {
        try (var db = TestDatabase.createDataSource();
                var fixture = new TestFixtures(db);
                var connection = db.getConnection()) {
            // A exigência de transação é verificada antes das validações de argumento.
            var error = assertThrows(IllegalStateException.class, () -> repository.enqueueFirmwareVersion(
                    connection, fixture.tenantId(), fixture.deviceId(), UUID.randomUUID(), UUID.randomUUID()));
            assertEquals("ENQUEUE_REQUIRES_TRANSACTION", error.getMessage());
            connection.setAutoCommit(false);
            // principalId e requestKey são obrigatórios para a auditoria do comando.
            assertThrows(NullPointerException.class, () -> repository.enqueueFirmwareVersion(
                    connection, fixture.tenantId(), fixture.deviceId(), null, UUID.randomUUID()));
            assertThrows(NullPointerException.class, () -> repository.enqueueFirmwareVersion(
                    connection, fixture.tenantId(), fixture.deviceId(), UUID.randomUUID(), null));
            connection.rollback();
        }
    }
}
