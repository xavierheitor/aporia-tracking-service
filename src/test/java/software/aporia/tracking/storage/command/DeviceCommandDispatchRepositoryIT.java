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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifica reserva concorrente, autorização condicional, rollback e revogação do token no PostgreSQL. */
class DeviceCommandDispatchRepositoryIT {

    /** Entrada da fila usada para preparar as solicitações de teste. */
    private final DeviceCommandRepository commands
            = new DeviceCommandRepository();

    /** Repositório que permite ao cenário simular reserva e transmissão. */
    private final DeviceCommandDispatchRepository dispatcher
            = new DeviceCommandDispatchRepository();

    /**
     * Verifica que a mesma solicitação só pode ser reservada uma vez.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldClaimCommandOnlyOnce() throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500010"
        ); Connection connection = dataSource.getConnection()) {

            UUID commandId = commands.enqueue(
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
                var claimed = dispatcher.claimNext(
                        connection
                ).orElseThrow();

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
                    commands.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );

            connection.setAutoCommit(false);

            try {
                assertTrue(
                        dispatcher.claimNext(connection).isEmpty()
                );
                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Exige uma transação aberta para adquirir a reserva.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRequireExplicitTransaction() throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); Connection connection = dataSource.getConnection()) {
            assertTrue(connection.getAutoCommit());

            var error = assertThrows(
                    IllegalStateException.class,
                    () -> dispatcher.claimNext(connection)
            );
            assertEquals("CLAIM_REQUIRES_TRANSACTION", error.getMessage());
        }
    }

    /**
     * Mantém duas conexões concorrentes e verifica que a segunda ignora o registro bloqueado.
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

            UUID commandId = commands.enqueue(
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
                var firstClaim = dispatcher.claimNext(first)
                        .orElseThrow();

                assertEquals(commandId, firstClaim.id());

                // A primeira transação ainda mantém o lock.
                // A segunda deve ignorar esse registro.
                assertTrue(
                        dispatcher.claimNext(second).isEmpty()
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
                    commands.findStatus(
                            setup,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );
        }
    }

    /**
     * Confere que desfazer a transação libera o comando para nova reserva.
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

            UUID commandId = commands.enqueue(
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
                var firstClaim = dispatcher.claimNext(first)
                        .orElseThrow();

                assertEquals(commandId, firstClaim.id());

                // A reserva ainda não foi confirmada.
                assertTrue(
                        dispatcher.claimNext(second).isEmpty()
                );

                // Simula falha do dispatcher antes do commit.
                first.rollback();

                // O comando deve voltar a ser elegível.
                var secondClaim = dispatcher.claimNext(second)
                        .orElseThrow();

                assertEquals(commandId, secondClaim.id());
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
     * Rejeita a conclusão feita com um token diferente do persistido.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectInvalidClaimToken() throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500030"
        ); Connection connection = dataSource.getConnection()) {

            UUID commandId = commands.enqueue(
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
                claimed = dispatcher.claimNext(connection)
                        .orElseThrow();

                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            assertEquals(commandId, claimed.id());

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
                    commands.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );
        }
    }

    /**
     * Confere a transição para sent quando a tentativa autorizada possui o token.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldMarkSentWithValidClaimToken() throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500031"
        ); Connection connection = dataSource.getConnection()) {

            UUID commandId = commands.enqueue(
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
                claimed = dispatcher.claimNext(connection)
                        .orElseThrow();

                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            assertEquals(commandId, claimed.id());
            assertTrue(
                    dispatcher.authorizeTransmission(
                            connection,
                            fixtures.tenantId(),
                            commandId,
                            claimed.claimToken()
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
                    commands.findStatus(
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
     * Revoga a reserva e verifica que o emissor antigo perde o direito de concluí-la.
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

            UUID commandId = commands.enqueue(
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
                claimed = dispatcher.claimNext(connection)
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
                    commands.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );

            // 5. Dispatcher antigo tenta concluir o comando.
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
                    commands.findStatus(
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
     * Confere o início da transmissão com o token da reserva atual.
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

            UUID commandId = commands.enqueue(
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
                claimed = dispatcher.claimNext(connection)
                        .orElseThrow();

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            assertEquals(commandId, claimed.id());

            assertTrue(
                    dispatcher.authorizeTransmission(
                            connection,
                            fixtures.tenantId(),
                            commandId,
                            claimed.claimToken()
                    )
            );

            assertEquals(
                    "transmitting",
                    commands.findStatus(
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
                            claimed.claimToken()
                    )
            );
        }
    }

    /**
     * Rejeita o início da transmissão com um token obsoleto.
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

            UUID commandId = commands.enqueue(
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
                dispatcher.claimNext(connection)
                        .orElseThrow();

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            assertFalse(
                    dispatcher.authorizeTransmission(
                            connection,
                            fixtures.tenantId(),
                            commandId,
                            UUID.randomUUID()
                    )
            );

            assertEquals(
                    "sending",
                    commands.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );
        }
    }

    /**
     * Restringe a seleção da fila ao dispositivo indicado.
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

            UUID commandId = commands.enqueue(
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
                var claimed = dispatcher.claimNextForDevice(
                        connection,
                        fixtures.tenantId(),
                        fixtures.deviceId()
                ).orElseThrow();

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
                    commands.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );
        }
    }

}
