package software.aporia.tracking.storage.command;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

import software.aporia.tracking.command.dispatcher.DispatchOwner;

/**
 * Expira comandos e reconcilia tentativas interrompidas respeitando o proprietário da transmissão.
 *
 * <p>As consultas são globais: não recebem tenant e alcançam todas as linhas no estado indicado. Cada método
 * executa apenas o {@code UPDATE} descrito e não confirma a transação; os que exigem atomicidade validam o
 * autocommit. A recuperação por idade de {@code transmitting} não revoga tentativas com proprietário, pois um
 * emissor ativo pode estar apenas pausado.</p>
 */
public final class DeviceCommandRecoveryRepository {

    /**
     * Marca como expirados os comandos que atingiram o prazo ainda na fila e devolve o número de
     * linhas afetadas.
     *
     * <p>É uma única instrução global, sem verificação de autocommit: pode rodar em transação ou em
     * autocommit do chamador. Somente o estado {@code queued} é afetado; reservas e comandos já enviados são
     * tratados pelos demais métodos.</p>
     *
     * @param connection conexão JDBC usada pelo chamador
     * @return quantidade de comandos cujo estado foi atualizado
     * @throws SQLException         se a operação JDBC falhar
     * @throws NullPointerException se a conexão for nula
     */
    public int expireQueued(
            Connection connection
    ) throws SQLException {

        Objects.requireNonNull(connection);

        // A cláusula de estado restringe a expiração à fila e evita tocar em
        // tentativas já reservadas ou enviadas.
        String sql = """
            UPDATE device_commands
            SET status = 'timeout',
                error_code = 'COMMAND_EXPIRED_IN_QUEUE'
            WHERE status = 'queued'
              AND expires_at <= NOW()
            """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            return statement.executeUpdate();
        }
    }

    /**
     * Invalida o token de reservas antigas e classifica o resultado como incerto.
     *
     * <p>Compara {@code claimed_at} com o instante atual menos a duração informada, grava {@code
     * delivery_unknown_at}, limpa o token e registra {@code SENDING_LEASE_EXPIRED}. A operação é global e
     * exige transação aberta, pois o chamador deve confirmar a invalidação junto das demais recuperações. Uma
     * reserva sem {@code claimed_at} não é alcançada.</p>
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param staleAfter idade mínima para que a reserva seja considerada antiga
     * @return quantidade de comandos cujo estado foi atualizado
     * @throws SQLException             se a operação JDBC falhar
     * @throws IllegalArgumentException se a duração não for positiva
     * @throws IllegalStateException    se a conexão estiver em autocommit
     * @throws NullPointerException     se a conexão ou a duração for nula
     */
    public int markStaleSendingAsUnknown(
            Connection connection,
            Duration staleAfter
    ) throws SQLException {

        Objects.requireNonNull(connection);
        Objects.requireNonNull(staleAfter);

        if (staleAfter.isZero()
                || staleAfter.isNegative()) {
            throw new IllegalArgumentException(
                    "INVALID_STALE_DURATION"
            );
        }

        if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                    "RECOVERY_REQUIRES_TRANSACTION"
            );
        }

        // O prazo é convertido em segundos fracionários e aplicado pelo relógio do
        // banco; somente reservas com claimed_at preenchido envelhecem.
        String sql = """
            UPDATE device_commands
            SET status = 'delivery_unknown',
                delivery_unknown_at = NOW(),
                claim_token = NULL,
                error_code = 'SENDING_LEASE_EXPIRED'
            WHERE status = 'sending'
              AND claimed_at IS NOT NULL
              AND claimed_at < NOW() - (
                  CAST(? AS DOUBLE PRECISION)
                  * INTERVAL '1 second'
              )
            """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setDouble(
                    1,
                    staleAfter.toNanos() / 1_000_000_000.0
            );

            return statement.executeUpdate();
        }
    }

    /**
     * Recupera por idade as transmissões legadas sem proprietário registrado.
     *
     * <p>Alcança apenas {@code transmitting} com {@code dispatch_owner} nulo; a recuperação de tentativas com
     * proprietário é feita por {@link #recoverOwner(Connection, DispatchOwner)} sob o bloqueio exclusivo do
     * emissor. Exige transação aberta e não confirma.</p>
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param staleAfter idade mínima para que a reserva seja considerada antiga
     * @return quantidade de comandos cujo estado foi atualizado
     * @throws SQLException             se a operação JDBC falhar
     * @throws IllegalArgumentException se a duração não for positiva
     * @throws IllegalStateException    se a conexão estiver em autocommit
     * @throws NullPointerException     se a conexão ou a duração for nula
     */
    public int markStaleTransmittingAsUnknown(
            Connection connection,
            Duration staleAfter
    ) throws SQLException {

        Objects.requireNonNull(connection);
        Objects.requireNonNull(staleAfter);

        if (staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException(
                    "INVALID_STALE_DURATION"
            );
        }

        if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                    "RECOVERY_REQUIRES_TRANSACTION"
            );
        }

        // O critério por idade só alcança transmissões legadas sem proprietário. Um emissor ativo pode estar pausado.
        String sql = """
        UPDATE device_commands
        SET status = 'delivery_unknown',
            delivery_unknown_at = NOW(),
            claim_token = NULL,
            error_code = 'TRANSMISSION_LEASE_EXPIRED'
        WHERE status = 'transmitting'
          AND dispatch_owner IS NULL
          AND transmission_started_at IS NOT NULL
          AND transmission_started_at <
              NOW() - (
                  CAST(? AS DOUBLE PRECISION)
                  * INTERVAL '1 second'
              )
        """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setDouble(
                    1,
                    staleAfter.toNanos() / 1_000_000_000.0
            );

            return statement.executeUpdate();
        }
    }


    /**
     * Reconcilia transmissões interrompidas do emissor que detém o bloqueio exclusivo.
     *
     * <p>O chamador deve manter o monitor de {@code owner}, exigido por {@link DispatchOwner#id()}, e o
     * bloqueio exclusivo do arquivo do emissor, que garante que nenhuma escrita concorrente ainda está em
     * curso. A atualização é global, filtra {@code status='transmitting'} e {@code dispatch_owner}, limpa o
     * token e não confirma a transação, embora não verifique o autocommit.</p>
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param owner proprietário do envio ou seu identificador persistido
     * @return quantidade de comandos cujo estado foi atualizado
     * @throws SQLException         se a operação JDBC falhar
     * @throws IllegalStateException se o monitor do proprietário não estiver retido
     *                               ou se o bloqueio estiver inválido
     * @throws NullPointerException  se a conexão ou o proprietário for nulo
     */
    public int recoverOwner(Connection connection, DispatchOwner owner) throws SQLException {
        // O proprietário delimitou a tentativa; o UPDATE alcança apenas as linhas
        // ainda em transmissão desse emissor.
        try (var statement = connection.prepareStatement("""
                UPDATE device_commands SET status='delivery_unknown', delivery_unknown_at=now(),
                    claim_token=NULL, error_code='TRANSMISSION_INTERRUPTED'
                WHERE status='transmitting' AND dispatch_owner=?
                """)) {
            statement.setObject(1, owner.id());
            return statement.executeUpdate();
        }
    }

    /**
     * Encerra por timeout a espera pela resposta dos comandos enviados cujo prazo venceu.
     *
     * <p>Depois do envio, {@code expires_at} funciona como prazo de resposta. A consulta é global, alcança
     * apenas {@code sent}, limpa o token e grava {@code RESPONSE_TIMEOUT}; repetir após a transição não
     * altera linhas. Não exige transação explícita nem confirma a transação do chamador.</p>
     *
     * @param connection conexão JDBC usada pelo chamador
     * @return quantidade de comandos cujo estado foi atualizado
     * @throws SQLException         se a operação JDBC falhar
     * @throws NullPointerException se a conexão for nula
     */
    public int expireSent(Connection connection) throws SQLException {
        // Somente comandos já enviados aguardam resposta; o prazo é o expires_at
        // original da solicitação.
        try (var statement = connection.prepareStatement("""
                UPDATE device_commands SET status='timeout', error_code='RESPONSE_TIMEOUT', claim_token=NULL
                WHERE status='sent' AND expires_at <= now()
                """)) {
            return statement.executeUpdate();
        }
    }
}
