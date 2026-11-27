package software.aporia.tracking.storage.command;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;
import software.aporia.tracking.support.CommandTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica reserva concorrente, autorização condicional, rollback e revogação do token no
 * PostgreSQL.
 *
 * <p>A reserva condiciona tenant, dispositivo e token da sessão e bloqueia a linha com
 * {@code FOR UPDATE SKIP LOCKED}; as transições seguintes exigem o {@code claim_token} emitido.
 * Cada teste cria o próprio tenant em {@link TestFixtures} e controla commit e rollback, que também
 * são a limpeza dos comandos criados. O schema é isolado por JVM, então varreduras globais de
 * recuperação são avaliadas com {@code >= 1}.</p>
 */
class DeviceCommandDispatchRepositoryIT {

    /** Entrada da fila usada para preparar as solicitações de teste. */
    private final DeviceCommandRepository commands
            = new DeviceCommandRepository();

    /** Repositório que permite ao cenário simular reserva e transmissão. */
    private final DeviceCommandDispatchRepository dispatcher
            = new DeviceCommandDispatchRepository();

    /**
     * Cenário: comando auditado necessário aos testes de transmissão. Ação: chama a entrada real de
     * produção dentro de uma transação, confirma e restaura o autocommit. Resultado: o UUID fica
     * commitado e elegível à reserva; uma falha desfaz a transação e é propagada.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param fixtures cadastro de teste que define o escopo do comando
     * @return UUID do comando confirmado
     * @throws SQLException se a operação JDBC falhar
     */
    private UUID enqueueFirmware(Connection connection, TestFixtures fixtures) throws SQLException {
        connection.setAutoCommit(false);
        try {
            UUID id = commands.enqueueFirmwareVersion(connection, fixtures.tenantId(), fixtures.deviceId(),
                    UUID.randomUUID(), UUID.randomUUID()).orElseThrow();
            connection.commit();
            return id;
        } catch (SQLException | RuntimeException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * Cenário: um comando em {@code queued}. Ação: reserva na primeira conexão, confirma e tenta
     * reservar novamente. Resultado: a primeira reserva devolve o comando com tentativa 1 e status
     * {@code sending}; a segunda devolve vazio porque a tentativa ativa exclui nova reserva.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldClaimCommandOnlyOnce() throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500010"
        ); Connection connection = dataSource.getConnection()) {

            // O INSERT direto é confirmado pelo autocommit antes de abrir a transação de reserva.
            UUID commandId = CommandTestFixtures.enqueue(
                    connection,
                    fixtures.tenantId(),
                    fixtures.deviceId(),
                    "suntech",
                    "TEST_COMMAND\r".getBytes(
                            StandardCharsets.US_ASCII
                    ),
                    Instant.now().plusSeconds(300)
            );

            connection.setAutoCommit(false);

            try {
                var claimed = CommandTestFixtures.claim(dispatcher, connection, fixtures).orElseThrow();

                assertEquals(commandId, claimed.id());
                assertEquals(fixtures.tenantId(), claimed.tenantId());
                assertEquals(fixtures.deviceId(), claimed.deviceId());
                assertEquals("suntech", claimed.protocol());
                assertEquals(1, claimed.attempts());

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            assertEquals(
                    "sending",
                    CommandTestFixtures.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );

            connection.setAutoCommit(false);

            try {
                assertTrue(
                        CommandTestFixtures.claim(dispatcher, connection, fixtures).isEmpty()
                );
                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Cenário: conexão em autocommit. Ação: tenta reservar. Resultado:
     * {@link IllegalStateException} com {@code CLAIM_REQUIRES_TRANSACTION} antes de qualquer SQL.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRequireExplicitTransaction() throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(dataSource); Connection connection = dataSource.getConnection()) {
            assertTrue(connection.getAutoCommit());

            // A checagem ocorre antes do SELECT, então nenhum bloqueio é adquirido.
            var error = assertThrows(
                    IllegalStateException.class,
                    () -> CommandTestFixtures.claim(dispatcher, connection, fixtures)
            );
            assertEquals("CLAIM_REQUIRES_TRANSACTION", error.getMessage());
        }
    }

    /**
     * Cenário: duas conexões abertas sobre a mesma fila. Ação: a primeira reserva e mantém a
     * transação aberta; a segunda tenta reservar antes do commit. Resultado: a segunda devolve vazio
     * sem esperar, pela cláusula {@code SKIP LOCKED}; após o commit, o comando fica em
     * {@code sending}.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldNotClaimLockedCommandFromAnotherConnection()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500011"
        ); Connection setup = dataSource.getConnection(); Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {

            UUID commandId = CommandTestFixtures.enqueue(
                    setup,
                    fixtures.tenantId(),
                    fixtures.deviceId(),
                    "suntech",
                    "TEST_COMMAND\r".getBytes(
                            StandardCharsets.US_ASCII
                    ),
                    Instant.now().plusSeconds(300)
            );

            first.setAutoCommit(false);
            second.setAutoCommit(false);

            try {
                var firstClaim = CommandTestFixtures.claim(dispatcher, first, fixtures)
                        .orElseThrow();

                assertEquals(commandId, firstClaim.id());

                // A primeira transação ainda mantém o lock.
                // SKIP LOCKED faz a segunda ignorar o registro em vez de esperar pela primeira.
                assertTrue(
                        CommandTestFixtures.claim(dispatcher, second, fixtures).isEmpty()
                );

                first.commit();
                second.commit();

            } catch (SQLException | RuntimeException e) {
                first.rollback();
                second.rollback();
                throw e;
            }

            assertEquals(
                    "sending",
                    CommandTestFixtures.findStatus(
                            setup,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );
        }
    }

    /**
     * Cenário: a primeira conexão reserva sem confirmar. Ação: desfaz essa transação e reserva na
     * segunda conexão. Resultado: o comando volta a {@code queued}; a segunda reserva devolve o mesmo
     * UUID com tentativa 1, porque o rollback desfez o incremento.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldReleaseClaimAfterRollback()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500012"
        ); Connection setup = dataSource.getConnection(); Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {

            UUID commandId = CommandTestFixtures.enqueue(
                    setup,
                    fixtures.tenantId(),
                    fixtures.deviceId(),
                    "suntech",
                    "TEST_COMMAND\r".getBytes(
                            StandardCharsets.US_ASCII
                    ),
                    Instant.now().plusSeconds(300)
            );

            first.setAutoCommit(false);
            second.setAutoCommit(false);

            try {
                var firstClaim = CommandTestFixtures.claim(dispatcher, first, fixtures)
                        .orElseThrow();

                assertEquals(commandId, firstClaim.id());

                // A reserva ainda não foi confirmada.
                assertTrue(
                        CommandTestFixtures.claim(dispatcher, second, fixtures).isEmpty()
                );

                // Simula falha do dispatcher antes do commit.
                first.rollback();

                // O comando deve voltar a ser elegível.
                var secondClaim = CommandTestFixtures.claim(dispatcher, second, fixtures)
                        .orElseThrow();

                assertEquals(commandId, secondClaim.id());
                // O rollback desfez o incremento da primeira tentativa; esta é contada como tentativa 1.
                assertEquals(1, secondClaim.attempts());

                second.commit();

            } catch (SQLException | RuntimeException e) {
                first.rollback();
                second.rollback();
                throw e;
            }
        }
    }

    /**
     * Cenário: comando reservado e confirmado. Ação: tenta marcar como enviado com um token
     * aleatório. Resultado: a atualização condicional devolve falso e o status permanece
     * {@code sending}.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectInvalidClaimToken() throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500030"
        ); Connection connection = dataSource.getConnection()) {

            UUID commandId = CommandTestFixtures.enqueue(
                    connection,
                    fixtures.tenantId(),
                    fixtures.deviceId(),
                    "suntech",
                    "TEST_COMMAND\r".getBytes(
                            StandardCharsets.US_ASCII
                    ),
                    Instant.now().plusSeconds(300)
            );

            connection.setAutoCommit(false);

            DeviceCommandDispatchRepository.ClaimedCommand claimed;

            try {
                claimed = CommandTestFixtures.claim(dispatcher, connection, fixtures)
                        .orElseThrow();

                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            assertEquals(commandId, claimed.id());

            // A transição exige token correto e status transmitting; aqui o comando continua em sending.
            assertFalse(
                    dispatcher.markSent(
                            connection,
                            fixtures.tenantId(),
                            commandId,
                            UUID.randomUUID()
                    )
            );

            assertEquals(
                    "sending",
                    CommandTestFixtures.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );
        }
    }

    /**
     * Cenário: comando auditado e reservado. Ação: autoriza a transmissão com a identidade do
     * cadastro, marca como enviado com o token da reserva e repete a conclusão. Resultado: o status
     * chega a {@code sent} na primeira tentativa; a repetição devolve falso porque a linha já saiu de
     * {@code transmitting}.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldMarkSentWithValidClaimToken() throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500031"
        ); Connection connection = dataSource.getConnection()) {

            UUID commandId = enqueueFirmware(connection, fixtures);

            connection.setAutoCommit(false);

            DeviceCommandDispatchRepository.ClaimedCommand claimed;

            try {
                claimed = CommandTestFixtures.claim(dispatcher, connection, fixtures)
                        .orElseThrow();

                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            assertEquals(commandId, claimed.id());
            // Cada chamada roda em autocommit, então a transição fica confirmada antes da próxima.
            assertTrue(
                    dispatcher.authorizeTransmission(
                            connection,
                            fixtures.tenantId(),
                            commandId,
                            claimed.claimToken(), UUID.randomUUID(), fixtures.deviceIdentity()
                    )
            );

            assertTrue(
                    dispatcher.markSent(
                            connection,
                            fixtures.tenantId(),
                            commandId,
                            claimed.claimToken()
                    )
            );

            assertEquals(
                    "sent",
                    CommandTestFixtures.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );

            // Não pode concluir a mesma tentativa duas vezes.
            assertFalse(
                    dispatcher.markSent(
                            connection,
                            fixtures.tenantId(),
                            commandId,
                            claimed.claimToken()
                    )
            );
        }
    }

    /**
     * Cenário: reserva antiga de um comando auditado. Ação: reserva e confirma, envelhece
     * {@code claimed_at}, executa a recuperação por idade e tenta concluir com o token antigo.
     * Resultado: a recuperação leva o comando a {@code delivery_unknown}, limpa o token e a
     * conclusão do emissor obsoleto é recusada.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectClaimTokenAfterRecovery()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500032"
        ); Connection connection = dataSource.getConnection()) {

            UUID commandId = CommandTestFixtures.enqueue(
                    connection,
                    fixtures.tenantId(),
                    fixtures.deviceId(),
                    "suntech",
                    "TEST_COMMAND\r".getBytes(
                            StandardCharsets.US_ASCII
                    ),
                    Instant.now().plusSeconds(300)
            );

            // 1. Dispatcher reserva o comando.
            connection.setAutoCommit(false);

            DeviceCommandDispatchRepository.ClaimedCommand claimed;

            try {
                claimed = CommandTestFixtures.claim(dispatcher, connection, fixtures)
                        .orElseThrow();

                assertEquals(commandId, claimed.id());
                assertNotNull(claimed.claimToken());

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            // 2. Simula uma reserva antiga.
            try (PreparedStatement statement
                    = connection.prepareStatement("""
                    UPDATE device_commands
                    SET claimed_at = ?
                    WHERE tenant_id = ?
                      AND id = ?
                      AND status = 'sending'
                    """)) {

                statement.setObject(
                        1,
                        Instant.now()
                                .minusSeconds(600)
                                .atOffset(
                                        java.time.ZoneOffset.UTC
                                )
                );

                statement.setObject(2, fixtures.tenantId());
                statement.setObject(3, commandId);

                assertEquals(1, statement.executeUpdate());
            }

            // 3. Recovery revoga a reserva.
            DeviceCommandRecoveryRepository recovery
                    = new DeviceCommandRecoveryRepository();

            connection.setAutoCommit(false);

            try {
                int recovered
                        = recovery.markStaleSendingAsUnknown(
                                connection,
                                java.time.Duration.ofMinutes(5)
                        );

                // A varredura é global ao schema e pode alcançar outras reservas antigas.
                assertTrue(recovered >= 1);

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            // 4. Confirma que o comando mudou de estado.
            assertEquals(
                    "delivery_unknown",
                    CommandTestFixtures.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );

            // 5. Dispatcher antigo tenta concluir o comando.
            // O token foi anulado pela recuperação, então a condição não encontra mais a linha.
            boolean updated = dispatcher.markSent(
                    connection,
                    fixtures.tenantId(),
                    commandId,
                    claimed.claimToken()
            );

            // A atualização deve ser rejeitada.
            assertFalse(updated);

            // O estado recuperado deve permanecer intacto.
            assertEquals(
                    "delivery_unknown",
                    CommandTestFixtures.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );

            // 6. Confirma que o token foi invalidado.
            try (PreparedStatement statement
                    = connection.prepareStatement("""
                    SELECT claim_token
                    FROM device_commands
                    WHERE tenant_id = ?
                      AND id = ?
                    """)) {

                statement.setObject(1, fixtures.tenantId());
                statement.setObject(2, commandId);

                try (ResultSet result = statement.executeQuery()) {

                    assertTrue(result.next());

                    assertNull(
                            result.getObject(
                                    "claim_token",
                                    UUID.class
                            )
                    );
                }
            }
        }
    }

    /**
     * Cenário: comando auditado e reservado. Ação: tenta autorizar com proprietário ou identidade
     * nulos, com identidade divergente, com outro tenant e, por fim, com o escopo correto; repete a
     * autorização. Resultado: entradas nulas falham com {@link NullPointerException}; escopos errados
     * devolvem falso; o escopo correto leva a {@code transmitting}; a repetição devolve falso.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldAuthorizeTransmissionWithValidToken()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500040"
        ); Connection connection = dataSource.getConnection()) {

            UUID commandId = enqueueFirmware(connection, fixtures);

            connection.setAutoCommit(false);

            DeviceCommandDispatchRepository.ClaimedCommand claimed;

            try {
                claimed = CommandTestFixtures.claim(dispatcher, connection, fixtures)
                        .orElseThrow();

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            assertEquals(commandId, claimed.id());

            // Nulo é recusado antes de montar o UPDATE.
            assertThrows(NullPointerException.class, () -> dispatcher.authorizeTransmission(
                    connection, fixtures.tenantId(), commandId, claimed.claimToken(), null, fixtures.deviceIdentity()));
            assertThrows(NullPointerException.class, () -> dispatcher.authorizeTransmission(
                    connection, fixtures.tenantId(), commandId, claimed.claimToken(), UUID.randomUUID(), null));
            // Identidade e tenant divergentes não satisfazem o EXISTS de devices.
            assertFalse(dispatcher.authorizeTransmission(connection, fixtures.tenantId(), commandId,
                    claimed.claimToken(), UUID.randomUUID(), "0000000000"));
            assertFalse(dispatcher.authorizeTransmission(connection, UUID.randomUUID(), commandId,
                    claimed.claimToken(), UUID.randomUUID(), fixtures.deviceIdentity()));

            assertTrue(
                    dispatcher.authorizeTransmission(
                            connection,
                            fixtures.tenantId(),
                            commandId,
                            claimed.claimToken(), UUID.randomUUID(), fixtures.deviceIdentity()
                    )
            );

            assertEquals(
                    "transmitting",
                    CommandTestFixtures.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );

            // Não pode autorizar duas vezes.
            assertFalse(
                    dispatcher.authorizeTransmission(
                            connection,
                            fixtures.tenantId(),
                            commandId,
                            claimed.claimToken(), UUID.randomUUID(), fixtures.deviceIdentity()
                    )
            );
        }
    }

    /**
     * Cenário: comando auditado e reservado. Ação: tenta autorizar a transmissão com um token
     * aleatório. Resultado: falso; o comando permanece em {@code sending}.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectTransmissionWithInvalidToken()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500041"
        ); Connection connection = dataSource.getConnection()) {

            UUID commandId = enqueueFirmware(connection, fixtures);

            connection.setAutoCommit(false);

            try {
                CommandTestFixtures.claim(dispatcher, connection, fixtures)
                        .orElseThrow();

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            // O UPDATE não encontra a linha porque o claim_token persistido é outro.
            assertFalse(
                    dispatcher.authorizeTransmission(
                            connection,
                            fixtures.tenantId(),
                            commandId,
                            UUID.randomUUID(), UUID.randomUUID(), fixtures.deviceIdentity()
                    )
            );

            assertEquals(
                    "sending",
                    CommandTestFixtures.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );
        }
    }

    /**
     * Cenário: comando em {@code queued} do dispositivo da fixture. Ação: reserva pela sessão desse
     * dispositivo. Resultado: a reserva devolve o comando do tenant e do dispositivo esperados, com
     * token emitido e tentativa 1; o status persistido é {@code sending}.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldClaimCommandForSpecificDevice()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500060"
        ); Connection connection = dataSource.getConnection()) {

            UUID commandId = CommandTestFixtures.enqueue(
                    connection,
                    fixtures.tenantId(),
                    fixtures.deviceId(),
                    "suntech",
                    "TEST_COMMAND\r".getBytes(
                            StandardCharsets.US_ASCII
                    ),
                    Instant.now().plusSeconds(300)
            );

            connection.setAutoCommit(false);

            try {
                // O WHERE combina tenant, dispositivo e token da sessão antes de bloquear a linha.
                var claimed = CommandTestFixtures.claim(dispatcher, connection, fixtures).orElseThrow();

                assertEquals(commandId, claimed.id());
                assertEquals(fixtures.tenantId(), claimed.tenantId());
                assertEquals(fixtures.deviceId(), claimed.deviceId());
                assertNotNull(claimed.claimToken());
                assertEquals(1, claimed.attempts());

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            assertEquals(
                    "sending",
                    CommandTestFixtures.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );
        }
    }

    /**
     * Cenário: comando inserido diretamente, sem {@code requested_by} e {@code request_key}. Ação:
     * reserva com a sessão da fixture e tenta autorizar a transmissão. Resultado: a autorização
     * devolve falso e o comando permanece em {@code sending} mesmo após o commit.
     *
     * @throws Exception se a preparação ou a verificação falhar
     */
    @Test
    void shouldRejectTransmissionWithoutAudit() throws Exception {
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db);
             var connection = db.getConnection()) {
            // O enqueue direto não preenche a auditoria que authorizeTransmission exige.
            UUID id = CommandTestFixtures.enqueue(connection, fixture.tenantId(), fixture.deviceId(), "suntech",
                    software.aporia.tracking.protocol.suntech.FirmwareVersionRequest.encode(fixture.deviceIdentity()),
                    Instant.now().plusSeconds(300));
            connection.setAutoCommit(false);
            try {
                var claim = CommandTestFixtures.claim(dispatcher, connection, fixture).orElseThrow();
                assertFalse(dispatcher.authorizeTransmission(connection, fixture.tenantId(), id,
                        claim.claimToken(), UUID.randomUUID(), fixture.deviceIdentity()));
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
            // O commit preserva o comando em sending; a limpeza fica com a fixture.
            assertEquals("sending", CommandTestFixtures.findStatus(connection, fixture.tenantId(), id).orElseThrow());
        }
    }

    /**
     * Cenário: comando auditado do tenant e do dispositivo da fixture. Ação: tenta reservar com
     * tenant, dispositivo e token de sessão divergentes e, por fim, com o escopo correto. Resultado:
     * as três tentativas erradas devolvem vazio; a correta devolve o comando esperado.
     *
     * @throws Exception se a preparação ou a verificação falhar
     */
    @Test
    void shouldRejectClaimFromWrongTenantDeviceOrSession() throws Exception {
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db);
             var connection = db.getConnection()) {
            UUID command = enqueueFirmware(connection, fixture);
            connection.setAutoCommit(false);
            try {
                // O filtro de escopo está no SQL, não em validação posterior.
                assertTrue(dispatcher.claimForSession(connection, UUID.randomUUID(), fixture.deviceId(), fixture.sessionToken()).isEmpty());
                assertTrue(dispatcher.claimForSession(connection, fixture.tenantId(), UUID.randomUUID(), fixture.sessionToken()).isEmpty());
                // O token de sessão só vale para o par tenant/dispositivo do cadastro.
                assertTrue(dispatcher.claimForSession(connection, fixture.tenantId(), fixture.deviceId(), UUID.randomUUID()).isEmpty());
                assertEquals(command, CommandTestFixtures.claim(dispatcher, connection, fixture).orElseThrow().id());
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

}
