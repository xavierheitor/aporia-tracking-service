package software.aporia.tracking.storage.command;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.support.CommandTestFixtures;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

/**
 * Verifica a recuperação de tentativas interrompidas e a expiração de comandos respeitando estado e
 * prazo.
 *
 * <p>As rotinas de recuperação atualizam todas as linhas elegíveis do schema, não apenas as do
 * tenant, então as asserções usam {@code >= 1}. Cada cenário cria um tenant próprio com
 * {@link TestFixtures} e controla commit e rollback; a limpeza final é feita pela fixture. Estados
 * históricos sem proprietário são montados por SQL de teste, pois a autorização de produção exige
 * dono.</p>
 */
class DeviceCommandRecoveryRepositoryIT {

        /** Repositório que permite ao cenário simular reserva e transmissão. */
        private final DeviceCommandDispatchRepository dispatcher = new DeviceCommandDispatchRepository();

        /** Operações de expiração e recuperação exercitadas pelo cenário. */
        private final DeviceCommandRecoveryRepository recovery = new DeviceCommandRecoveryRepository();

        /**
         * Cenário: comando em {@code queued} cujo estado e prazo o teste ajustará. Ação: insere um
         * comando sem auditoria pela fixture de teste. Resultado: devolve o UUID; o commit depende do
         * autocommit da conexão recebida.
         *
         * @param connection conexão JDBC usada pelo chamador
         * @param fixtures   cadastro de teste utilizado no cenário
         * @return UUID do comando inserido
         * @throws Exception se a preparação, execução ou verificação do cenário falhar
         */
        private UUID enqueue(
                        Connection connection,
                        TestFixtures fixtures) throws Exception {

                return CommandTestFixtures.enqueue(
                                connection,
                                fixtures.tenantId(),
                                fixtures.deviceId(),
                                "suntech",
                                "TEST_COMMAND\r".getBytes(
                                                StandardCharsets.US_ASCII),
                                Instant.now().plusSeconds(300));
        }

        /**
         * Cenário: transmissão criada pela autorização atual, que sempre grava proprietário. Ação:
         * muda o comando para {@code transmitting}, zera {@code dispatch_owner} e preserva o token,
         * reproduzindo um registro anterior ao controle por proprietário. Resultado: a linha fica
         * elegível à recuperação por idade, restrita ao tenant, dispositivo e token informados.
         *
         * @param connection conexão JDBC usada pelo chamador
         * @param fixtures cadastro de teste utilizado no cenário
         * @param commandId identificador do comando na fila
         * @param claimToken token que identifica a reserva vigente
         * @throws SQLException se a operação JDBC falhar
         */
        private void simulateLegacyTransmission(Connection connection, TestFixtures fixtures,
                        UUID commandId, UUID claimToken) throws SQLException {
                // dispatch_owner nulo é o formato legado que a recuperação por idade alcança.
                try (var update = connection.prepareStatement(
                                """
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
         * Cenário: necessidade de simular envelhecimento sem espera real. Ação: atualiza a coluna de
         * tempo permitida ({@code expires_at} ou {@code claimed_at}) no comando informado. Resultado:
         * o prazo persistido passa a ser o instante do teste; coluna fora da lista é recusada com
         * {@code INVALID_TEST_COLUMN}.
         *
         * @param connection conexão JDBC usada pelo chamador
         * @param commandId  identificador do comando na fila
         * @param column     coluna de tempo que o teste ajusta
         * @param instant    instante simulado pelo teste
         * @throws Exception se a preparação, execução ou verificação do cenário falhar
         */
        private void setCommandTime(
                        Connection connection,
                        UUID commandId,
                        String column,
                        Instant instant) throws Exception {

                if (!column.equals("expires_at")
                                && !column.equals("claimed_at")) {
                        throw new IllegalArgumentException(
                                        "INVALID_TEST_COLUMN");
                }

                // Apenas o id do comando do cenário é usado; o UPDATE não recebe filtro de tenant.
                String sql = """
                                UPDATE device_commands
                                SET %s = ?
                                WHERE id = ?
                                """.formatted(column);

                try (PreparedStatement statement = connection.prepareStatement(sql)) {

                        statement.setObject(
                                        1,
                                        instant.atOffset(
                                                        java.time.ZoneOffset.UTC));

                        statement.setObject(2, commandId);
                        statement.executeUpdate();
                }
        }

        /**
         * Cenário: comando em {@code queued} criado há 10 minutos e expirado há 1 minuto. Ação:
         * executa a expiração da fila. Resultado: o comando passa a {@code timeout}, estado definido
         * pela rotina com {@code COMMAND_EXPIRED_IN_QUEUE}; a contagem é {@code >= 1} porque a
         * varredura alcança todo o schema.
         *
         * @throws Exception se a preparação, execução ou verificação do cenário falhar
         */
        @Test
        void shouldExpireQueuedCommand() throws Exception {

                try (
                                var dataSource = TestDatabase.createDataSource();
                                var fixtures = new TestFixtures(
                                                dataSource, "9876500020");
                                Connection connection = dataSource.getConnection()) {

                        UUID id = enqueue(connection, fixtures);

                        // Simula um comando criado há 10 minutos
                        // e que expirou há 1 minuto.
                        //
                        // Preserva a regra:
                        // created_at < expires_at.
                        try (PreparedStatement statement = connection.prepareStatement("""
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
                                                                .atOffset(java.time.ZoneOffset.UTC));

                                statement.setObject(
                                                2,
                                                now.minusSeconds(600)
                                                                .atOffset(java.time.ZoneOffset.UTC));

                                statement.setObject(
                                                3,
                                                now.minusSeconds(60)
                                                                .atOffset(java.time.ZoneOffset.UTC));

                                statement.setObject(4, id);
                                statement.setObject(5, fixtures.tenantId());

                                assertEquals(1, statement.executeUpdate());
                        }

                        int updated = recovery.expireQueued(connection);

                        // A conexão está em autocommit: a atualização já foi confirmada pelo driver.
                        assertTrue(updated >= 1);

                        assertEquals(
                                        "timeout",
                                        CommandTestFixtures.findStatus(
                                                        connection,
                                                        fixtures.tenantId(),
                                                        id).orElseThrow());
                }
        }

        /**
         * Cenário: comando em {@code queued} dentro do prazo. Ação: executa a expiração da fila.
         * Resultado: o comando permanece {@code queued}.
         *
         * @throws Exception se a preparação, execução ou verificação do cenário falhar
         */
        @Test
        void shouldNotExpireValidQueuedCommand()
                        throws Exception {

                try (
                                var dataSource = TestDatabase.createDataSource();
                                var fixtures = new TestFixtures(
                                                dataSource, "9876500021");
                                Connection connection = dataSource.getConnection()) {

                        UUID id = enqueue(connection, fixtures);

                        recovery.expireQueued(connection);

                        assertEquals(
                                        "queued",
                                        CommandTestFixtures.findStatus(
                                                        connection,
                                                        fixtures.tenantId(),
                                                        id).orElseThrow());
                }
        }

        /**
         * Cenário: comando reservado cujo {@code claimed_at} foi envelhecido em 10 minutos. Ação:
         * executa a recuperação de reservas antigas em {@code sending}. Resultado: o comando passa a
         * {@code delivery_unknown}; a contagem é {@code >= 1} pela varredura global.
         *
         * @throws Exception se a preparação, execução ou verificação do cenário falhar
         */
        @Test
        void shouldMarkStaleSendingAsUnknown()
                        throws Exception {

                try (
                                var dataSource = TestDatabase.createDataSource();
                                var fixtures = new TestFixtures(
                                                dataSource, "9876500022");
                                Connection connection = dataSource.getConnection()) {

                        UUID id = enqueue(connection, fixtures);

                        connection.setAutoCommit(false);

                        try {
                                assertEquals(
                                                id,
                                                CommandTestFixtures.claim(dispatcher, connection, fixtures)
                                                                .orElseThrow().id());

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
                                        Instant.now().minusSeconds(600));

                        connection.setAutoCommit(false);

                        try {
                                int updated = recovery.markStaleSendingAsUnknown(
                                                connection,
                                                Duration.ofMinutes(5));

                                // A recuperação exige transação e revoga o token da reserva antiga.
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
                                                        id).orElseThrow());
                }
        }

        /**
         * Cenário: comando reservado agora, sem envelhecimento. Ação: executa a recuperação por
         * idade. Resultado: o comando permanece {@code sending}, porque a reserva ainda está dentro do
         * limite.
         *
         * @throws Exception se a preparação, execução ou verificação do cenário falhar
         */
        @Test
        void shouldNotRecoverRecentSendingCommand()
                        throws Exception {

                try (
                                var dataSource = TestDatabase.createDataSource();
                                var fixtures = new TestFixtures(
                                                dataSource, "9876500023");
                                Connection connection = dataSource.getConnection()) {

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
                                                Duration.ofMinutes(5));

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
                                                        id).orElseThrow());
                }
        }

        /**
         * Cenário: conexão em autocommit. Ação: tenta recuperar reservas antigas. Resultado:
         * {@link IllegalStateException} com {@code RECOVERY_REQUIRES_TRANSACTION}, sem executar o
         * UPDATE.
         *
         * @throws Exception se a preparação, execução ou verificação do cenário falhar
         */
        @Test
        void shouldRejectRecoveryWithoutTransaction()
                        throws Exception {

                try (
                                var dataSource = TestDatabase.createDataSource();
                                Connection connection = dataSource.getConnection()) {

                        var error = assertThrows(
                                        IllegalStateException.class,
                                        () -> recovery.markStaleSendingAsUnknown(
                                                        connection,
                                                        Duration.ofMinutes(5)));
                        assertEquals("RECOVERY_REQUIRES_TRANSACTION", error.getMessage());
                }
        }

        /**
         * Cenário: conexão em autocommit. Ação: tenta recuperar com {@link Duration#ZERO}. Resultado:
         * {@link IllegalArgumentException} com {@code INVALID_STALE_DURATION}, antes de qualquer SQL.
         *
         * @throws Exception se a preparação, execução ou verificação do cenário falhar
         */
        @Test
        void shouldRejectInvalidRecoveryDuration()
                        throws Exception {

                try (
                                var dataSource = TestDatabase.createDataSource();
                                Connection connection = dataSource.getConnection()) {

                        var error = assertThrows(
                                        IllegalArgumentException.class,
                                        () -> recovery.markStaleSendingAsUnknown(
                                                        connection,
                                                        Duration.ZERO));
                        assertEquals("INVALID_STALE_DURATION", error.getMessage());
                }
        }

        /**
         * Cenário: transmissão legada sem proprietário, iniciada há 10 minutos, com reserva ainda
         * vigente. Ação: executa a recuperação por idade e depois tenta concluir com o token antigo.
         * Resultado: o comando passa a {@code delivery_unknown} e a conclusão do emissor obsoleto é
         * recusada.
         *
         * @throws Exception se a preparação, execução ou verificação do cenário falhar
         */
        @Test
        void shouldRecoverStaleTransmittingCommand()
                        throws Exception {

                try (
                                var dataSource = TestDatabase.createDataSource();
                                var fixtures = new TestFixtures(
                                                dataSource, "9876500050");
                                Connection connection = dataSource.getConnection()) {

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
                        try (PreparedStatement statement = connection.prepareStatement("""
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
                                                                                java.time.ZoneOffset.UTC));
                                statement.setObject(2, fixtures.tenantId());
                                statement.setObject(3, commandId);

                                assertEquals(1, statement.executeUpdate());
                        }

                        connection.setAutoCommit(false);

                        try {
                                int recovered = recovery.markStaleTransmittingAsUnknown(
                                                connection,
                                                Duration.ofMinutes(5));

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
                                                        commandId).orElseThrow());

                        // O dispatcher antigo não pode concluir a tentativa.
                        assertFalse(
                                        dispatcher.markSent(
                                                        connection,
                                                        fixtures.tenantId(),
                                                        commandId,
                                                        claimed.claimToken()));
                }
        }

        /**
         * Cenário: transmissão legada sem proprietário, iniciada agora. Ação: executa a recuperação
         * por idade. Resultado: o comando permanece {@code transmitting}, pois está dentro do limite.
         *
         * @throws Exception se a preparação, execução ou verificação do cenário falhar
         */
        @Test
        void shouldNotRecoverRecentTransmittingCommand()
                        throws Exception {

                try (
                                var dataSource = TestDatabase.createDataSource();
                                var fixtures = new TestFixtures(
                                                dataSource, "9876500051");
                                Connection connection = dataSource.getConnection()) {

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
                                                Duration.ofMinutes(5));

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
                                                        commandId).orElseThrow());
                }
        }

        /**
         * Cenário: transmissão legada antiga. Ação: executa a recuperação duas vezes na mesma
         * transação. Resultado: a primeira atualiza ao menos uma linha; a segunda devolve zero, porque
         * a linha já saiu de {@code transmitting}.
         *
         * @throws Exception se a preparação, execução ou verificação do cenário falhar
         */
        @Test
        void shouldNotRecoverSameTransmissionTwice()
                        throws Exception {

                try (
                                var dataSource = TestDatabase.createDataSource();
                                var fixtures = new TestFixtures(
                                                dataSource, "9876500052");
                                Connection connection = dataSource.getConnection()) {

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

                        try (PreparedStatement statement = connection.prepareStatement("""
                                        UPDATE device_commands
                                        SET transmission_started_at = ?
                                        WHERE tenant_id = ?
                                          AND id = ?
                                        """)) {

                                statement.setObject(
                                                1,
                                                Instant.now().minusSeconds(600)
                                                                .atOffset(
                                                                                java.time.ZoneOffset.UTC));
                                statement.setObject(2, fixtures.tenantId());
                                statement.setObject(3, commandId);

                                assertEquals(1, statement.executeUpdate());
                        }

                        connection.setAutoCommit(false);

                        try {
                                int first = recovery.markStaleTransmittingAsUnknown(
                                                connection,
                                                Duration.ofMinutes(5));

                                // O comando já está em delivery_unknown.
                                int second = recovery.markStaleTransmittingAsUnknown(
                                                connection,
                                                Duration.ofMinutes(5));

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
         * Cenário: conexão em autocommit. Ação: tenta recuperar transmissões antigas. Resultado:
         * {@link IllegalStateException} com {@code RECOVERY_REQUIRES_TRANSACTION}, antes do UPDATE.
         *
         * @throws Exception se a preparação, execução ou verificação do cenário falhar
         */
        @Test
        void shouldRejectTransmittingRecoveryWithoutTransaction()
                        throws Exception {

                try (
                                var dataSource = TestDatabase.createDataSource();
                                Connection connection = dataSource.getConnection()) {

                        assertTrue(connection.getAutoCommit());

                        var error = assertThrows(
                                        IllegalStateException.class,
                                        () -> recovery.markStaleTransmittingAsUnknown(
                                                        connection,
                                                        Duration.ofMinutes(5)));
                        assertEquals("RECOVERY_REQUIRES_TRANSACTION", error.getMessage());
                }
        }

}
