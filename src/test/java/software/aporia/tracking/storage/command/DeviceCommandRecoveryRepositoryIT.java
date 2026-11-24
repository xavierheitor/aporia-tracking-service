package software.aporia.tracking.storage.command;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;
import software.aporia.tracking.support.CommandTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifica a recuperação de tentativas interrompidas e a expiração de comandos respeitando estado e prazo. */
class DeviceCommandRecoveryRepositoryIT {

    /** Entrada da fila usada para preparar as solicitações de teste. */
    private final DeviceCommandRepository commands
            = new DeviceCommandRepository();

    /** Repositório que permite ao cenário simular reserva e transmissão. */
    private final DeviceCommandDispatchRepository dispatcher
            = new DeviceCommandDispatchRepository();

    /** Operações de expiração e recuperação exercitadas pelo cenário. */
    private final DeviceCommandRecoveryRepository recovery
            = new DeviceCommandRecoveryRepository();

    /**
     * Enfileira o comando sobre o qual o teste manipulará estados e prazos.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param fixtures cadastro de teste utilizado no cenário
     * @return UUID do comando inserido
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private UUID enqueue(
            Connection connection,
            TestFixtures fixtures
    ) throws Exception {

        return CommandTestFixtures.enqueue(
                connection,
                fixtures.tenantId(),
                fixtures.deviceId(),
                "suntech",
                "TEST_COMMAND\r".getBytes(
                        StandardCharsets.US_ASCII
                ),
                Instant.now().plusSeconds(300)
        );
    }

    /**
     * Monta um registro histórico sem proprietário para testar a recuperação legada.
     * A autorização atual exige proprietário; dados antigos são preparados explicitamente.
     */
    private void simulateLegacyTransmission(Connection connection, TestFixtures fixtures,
                                            UUID commandId, UUID claimToken) throws SQLException {
        try (var update = connection.prepareStatement("""
                UPDATE device_commands SET status='transmitting', transmission_started_at=now(), dispatch_owner=NULL
                WHERE tenant_id=? AND device_id=? AND id=? AND claim_token=? AND status='sending'
                """)) {
            update.setObject(1, fixtures.tenantId());
            update.setObject(2, fixtures.deviceId());
            update.setObject(3, commandId);
            update.setObject(4, claimToken);
            assertEquals(1, update.executeUpdate());
        }
    }

    /**
     * Ajusta o relógio persistido do comando para simular envelhecimento sem espera real.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param commandId identificador do comando na fila
     * @param column coluna de tempo que o teste ajusta
     * @param instant instante simulado pelo teste
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private void setCommandTime(
            Connection connection,
            UUID commandId,
            String column,
            Instant instant
    ) throws Exception {

        if (!column.equals("expires_at")
                && !column.equals("claimed_at")) {
            throw new IllegalArgumentException(
                    "INVALID_TEST_COLUMN"
            );
        }

        String sql = """
            UPDATE device_commands
            SET %s = ?
            WHERE id = ?
            """.formatted(column);

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setObject(
                    1,
                    instant.atOffset(
                            java.time.ZoneOffset.UTC
                    )
            );

            statement.setObject(2, commandId);
            statement.executeUpdate();
        }
    }

    /**
     * Confere a expiração de um comando ainda na fila.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldExpireQueuedCommand() throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500020"
        ); Connection connection = dataSource.getConnection()) {

            UUID id = enqueue(connection, fixtures);

            // Simula um comando criado há 10 minutos
            // e que expirou há 1 minuto.
            //
            // Preserva a regra:
            // created_at < expires_at.
            try (PreparedStatement statement
                    = connection.prepareStatement("""
                    UPDATE device_commands
                    SET created_at = ?,
                        available_at = ?,
                        expires_at = ?
                    WHERE id = ?
                      AND tenant_id = ?
                    """)) {

                Instant now = Instant.now();

                statement.setObject(
                        1,
                        now.minusSeconds(600)
                                .atOffset(java.time.ZoneOffset.UTC)
                );

                statement.setObject(
                        2,
                        now.minusSeconds(600)
                                .atOffset(java.time.ZoneOffset.UTC)
                );

                statement.setObject(
                        3,
                        now.minusSeconds(60)
                                .atOffset(java.time.ZoneOffset.UTC)
                );

                statement.setObject(4, id);
                statement.setObject(5, fixtures.tenantId());

                assertEquals(1, statement.executeUpdate());
            }

            int updated = recovery.expireQueued(connection);

            assertTrue(updated >= 1);

            assertEquals(
                    "timeout",
                    CommandTestFixtures.findStatus(
                            connection,
                            fixtures.tenantId(),
                            id
                    ).orElseThrow()
            );
        }
    }

    /**
     * Preserva o comando cujo prazo na fila ainda está vigente.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldNotExpireValidQueuedCommand()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500021"
        ); Connection connection = dataSource.getConnection()) {

            UUID id = enqueue(connection, fixtures);

            recovery.expireQueued(connection);

            assertEquals(
                    "queued",
                    CommandTestFixtures.findStatus(
                            connection,
                            fixtures.tenantId(),
                            id
                    ).orElseThrow()
            );
        }
    }

    /**
     * Classifica como incerta uma reserva antiga interrompida em sending.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldMarkStaleSendingAsUnknown()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500022"
        ); Connection connection = dataSource.getConnection()) {

            UUID id = enqueue(connection, fixtures);

            connection.setAutoCommit(false);

            try {
                assertEquals(
                        id,
                        CommandTestFixtures.claim(dispatcher, connection, fixtures).orElseThrow().id()
                );

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            setCommandTime(
                    connection,
                    id,
                    "claimed_at",
                    Instant.now().minusSeconds(600)
            );

            connection.setAutoCommit(false);

            try {
                int updated
                        = recovery.markStaleSendingAsUnknown(
                                connection,
                                Duration.ofMinutes(5)
                        );

                assertTrue(updated >= 1);

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            assertEquals(
                    "delivery_unknown",
                    CommandTestFixtures.findStatus(
                            connection,
                            fixtures.tenantId(),
                            id
                    ).orElseThrow()
            );
        }
    }

    /**
     * Preserva uma reserva recente durante a recuperação por idade.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldNotRecoverRecentSendingCommand()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500023"
        ); Connection connection = dataSource.getConnection()) {

            UUID id = enqueue(connection, fixtures);

            connection.setAutoCommit(false);

            try {
                CommandTestFixtures.claim(dispatcher, connection, fixtures).orElseThrow();
                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(false);

            try {
                recovery.markStaleSendingAsUnknown(
                        connection,
                        Duration.ofMinutes(5)
                );

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
                            id
                    ).orElseThrow()
            );
        }
    }

    /**
     * Exige transação explícita na recuperação de reservas.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectRecoveryWithoutTransaction()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); Connection connection = dataSource.getConnection()) {

            var error = assertThrows(
                    IllegalStateException.class,
                    () -> recovery.markStaleSendingAsUnknown(
                            connection,
                            Duration.ofMinutes(5)
                    )
            );
            assertEquals("RECOVERY_REQUIRES_TRANSACTION", error.getMessage());
        }
    }

    /**
     * Rejeita uma duração inválida para o limite de antiguidade.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectInvalidRecoveryDuration()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); Connection connection = dataSource.getConnection()) {

            var error = assertThrows(
                    IllegalArgumentException.class,
                    () -> recovery.markStaleSendingAsUnknown(
                            connection,
                            Duration.ZERO
                    )
            );
            assertEquals("INVALID_STALE_DURATION", error.getMessage());
        }
    }

    /**
     * Recupera uma transmissão legada que excedeu o limite de idade.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRecoverStaleTransmittingCommand()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500050"
        ); Connection connection = dataSource.getConnection()) {

            UUID commandId = enqueue(connection, fixtures);

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

            simulateLegacyTransmission(connection, fixtures, commandId, claimed.claimToken());

            // Simula uma transmissão iniciada há 10 minutos.
            try (PreparedStatement statement
                    = connection.prepareStatement("""
                    UPDATE device_commands
                    SET transmission_started_at = ?
                    WHERE tenant_id = ?
                      AND id = ?
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

            connection.setAutoCommit(false);

            try {
                int recovered
                        = recovery.markStaleTransmittingAsUnknown(
                                connection,
                                Duration.ofMinutes(5)
                        );

                assertTrue(recovered >= 1);

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            assertEquals(
                    "delivery_unknown",
                    CommandTestFixtures.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );

            // O dispatcher antigo não pode concluir a tentativa.
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
     * Preserva uma transmissão recente.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldNotRecoverRecentTransmittingCommand()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500051"
        ); Connection connection = dataSource.getConnection()) {

            UUID commandId = enqueue(connection, fixtures);

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

            simulateLegacyTransmission(connection, fixtures, commandId, claimed.claimToken());

            connection.setAutoCommit(false);

            try {
                recovery.markStaleTransmittingAsUnknown(
                        connection,
                        Duration.ofMinutes(5)
                );

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            assertEquals(
                    "transmitting",
                    CommandTestFixtures.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );
        }
    }

    /**
     * Confere a idempotência da recuperação sobre a mesma tentativa.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldNotRecoverSameTransmissionTwice()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500052"
        ); Connection connection = dataSource.getConnection()) {

            UUID commandId = enqueue(connection, fixtures);

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

            simulateLegacyTransmission(connection, fixtures, commandId, claimed.claimToken());

            try (PreparedStatement statement
                    = connection.prepareStatement("""
                    UPDATE device_commands
                    SET transmission_started_at = ?
                    WHERE tenant_id = ?
                      AND id = ?
                    """)) {

                statement.setObject(
                        1,
                        Instant.now().minusSeconds(600)
                                .atOffset(
                                        java.time.ZoneOffset.UTC
                                )
                );
                statement.setObject(2, fixtures.tenantId());
                statement.setObject(3, commandId);

                assertEquals(1, statement.executeUpdate());
            }

            connection.setAutoCommit(false);

            try {
                int first
                        = recovery.markStaleTransmittingAsUnknown(
                                connection,
                                Duration.ofMinutes(5)
                        );

                // O comando já está em delivery_unknown.
                int second
                        = recovery.markStaleTransmittingAsUnknown(
                                connection,
                                Duration.ofMinutes(5)
                        );

                assertTrue(first >= 1);
                assertEquals(0, second);

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Exige transação explícita ao recuperar transmissões antigas.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectTransmittingRecoveryWithoutTransaction()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); Connection connection = dataSource.getConnection()) {

            assertTrue(connection.getAutoCommit());

            var error = assertThrows(
                    IllegalStateException.class,
                    () -> recovery.markStaleTransmittingAsUnknown(
                            connection,
                            Duration.ofMinutes(5)
                    )
            );
            assertEquals("RECOVERY_REQUIRES_TRANSACTION", error.getMessage());
        }
    }

}
