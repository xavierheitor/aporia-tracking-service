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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifica a recuperação de tentativas interrompidas e a expiração de comandos respeitando estado e prazo. */
class DeviceCommandRecoveryRepositoryIT {

    private final DeviceCommandRepository commands
            = new DeviceCommandRepository();

    private final DeviceCommandDispatchRepository dispatcher
            = new DeviceCommandDispatchRepository();

    private final DeviceCommandRecoveryRepository recovery
            = new DeviceCommandRecoveryRepository();

    private UUID enqueue(
            Connection connection,
            TestFixtures fixtures
    ) throws Exception {

        return commands.enqueue(
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
                    commands.findStatus(
                            connection,
                            fixtures.tenantId(),
                            id
                    ).orElseThrow()
            );
        }
    }

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
                    commands.findStatus(
                            connection,
                            fixtures.tenantId(),
                            id
                    ).orElseThrow()
            );
        }
    }

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
                        dispatcher.claimNext(
                                connection
                        ).orElseThrow().id()
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
                    commands.findStatus(
                            connection,
                            fixtures.tenantId(),
                            id
                    ).orElseThrow()
            );
        }
    }

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
                dispatcher.claimNext(connection).orElseThrow();
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
                    commands.findStatus(
                            connection,
                            fixtures.tenantId(),
                            id
                    ).orElseThrow()
            );
        }
    }

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
                    commands.findStatus(
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
                claimed = dispatcher.claimNext(connection)
                        .orElseThrow();

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            assertTrue(
                    dispatcher.authorizeTransmission(
                            connection,
                            fixtures.tenantId(),
                            commandId,
                            claimed.claimToken()
                    )
            );

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
                    commands.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );
        }
    }

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
                claimed = dispatcher.claimNext(connection)
                        .orElseThrow();
                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            connection.setAutoCommit(true);

            assertTrue(
                    dispatcher.authorizeTransmission(
                            connection,
                            fixtures.tenantId(),
                            commandId,
                            claimed.claimToken()
                    )
            );

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
