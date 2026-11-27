package software.aporia.tracking.command.dispatcher;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.slf4j.LoggerFactory;

import software.aporia.tracking.observability.metrics.CommandMetrics;
import software.aporia.tracking.protocol.suntech.FirmwareVersionRequest;
import software.aporia.tracking.storage.command.DeviceCommandDispatchRepository.Failure;
import software.aporia.tracking.storage.command.DeviceCommandDispatchRepository;
import software.aporia.tracking.storage.command.DeviceCommandRecoveryRepository;

/**
 * Coordena a reserva no PostgreSQL e a escrita na sessão TCP. A reserva e a autorização acontecem em uma transação; a
 * escrita ocorre fora dela e o resultado é persistido em outra conexão.
 *
 * <p>
 * Colaboradores: o {@link DeviceCommandDispatchRepository} reserva e condiciona as transições ao token; o
 * {@link CommandTransport} entrega o endpoint da sessão; o {@link DispatchOwner} fornece o monitor e o UUID que
 * identificam este emissor; o {@link DeviceCommandRecoveryRepository} reconcilia tentativas interrompidas.
 * </p>
 *
 * <p>
 * Concorrência: {@code dispatchNext} e {@code recover} sincronizam no proprietário, de modo que só há uma tentativa ou
 * recuperação por emissor. O monitor é mantido durante a escrita no socket, que pode bloquear por até o prazo de
 * escrita da sessão. O bloqueio de linha do cadastro impede a troca de sessão entre a reserva e o commit da
 * autorização.
 * </p>
 *
 * <p>
 * Limites: não há reenvio automático. {@link DispatchResult#SENT} significa apenas escrita local concluída, não entrega
 * ou execução. Uma falha de escrita é classificada como {@link DispatchResult#DELIVERY_UNKNOWN} porque pode ter
 * ocorrido envio parcial. Exceções de runtime na escrita não são persistidas aqui; a tentativa permanece em
 * {@code transmitting} e a recuperação a classifica como incerta.
 * </p>
 */
public final class CommandDispatcher {

    /**
     * Resultado observado pelo dispatcher para uma tentativa. Não é o estado persistido do comando, embora corresponda
     * às transições aplicadas.
     */
    public enum DispatchResult {
        /** Nenhuma sessão disponível para iniciar o envio. */
        DEVICE_OFFLINE,
        /** A consulta da fila não encontrou uma solicitação elegível. */
        NO_COMMAND,
        /**
         * A escrita local terminou e o comando foi marcado como {@code sent}; não confirma entrega nem execução.
         */
        SENT,
        /** A tentativa foi encerrada antes de transmitir o comando. */
        DELIVERY_FAILED,
        /** A transmissão pode ter começado e seu resultado é incerto. */
        DELIVERY_UNKNOWN,
        /** O token da reserva deixou de autorizar a transição solicitada. */
        CLAIM_LOST
    }

    /** Fonte de conexões JDBC usadas pela instância. */
    private final DataSource dataSource;
    /** Repositório utilizado para as operações sobre comandos. */
    private final DeviceCommandDispatchRepository repository;
    /** Resolve o canal disponível para escrever no equipamento. */
    private final CommandTransport transport;
    /** Proprietário cujo bloqueio delimita as tentativas deste emissor. */
    private final DispatchOwner owner;

    /** Métricas de tentativa, escrita e recuperação. */
    private final CommandMetrics metrics;

    /**
     * Vincula a fila e o transporte ao proprietário responsável pelas tentativas deste emissor.
     *
     * @param dataSource fonte de conexões JDBC para a operação
     * @param repository repositório que controla a reserva e suas transições
     * @param transport  canal usado para alcançar a sessão do dispositivo
     * @param owner      proprietário do envio, cujo monitor serializa as rodadas
     * @param metrics    métricas de tentativa, escrita e recuperação
     * @throws NullPointerException se qualquer dependência for nula
     */
    public CommandDispatcher(
            DataSource dataSource,
            DeviceCommandDispatchRepository repository,
            CommandTransport transport,
            DispatchOwner owner,
            CommandMetrics metrics) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.repository = Objects.requireNonNull(repository);
        this.transport = Objects.requireNonNull(transport);
        this.owner = Objects.requireNonNull(owner);
        this.metrics = Objects.requireNonNull(metrics);

    }

    /**
     * Executa uma tentativa sob o monitor do proprietário: verifica a sessão, reserva o comando, autoriza a transmissão
     * e, após o commit, escreve no socket. A disponibilidade da sessão é testada antes da reserva para manter comandos
     * offline na fila.
     *
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @return desfecho da tentativa observado pelo dispatcher
     * @throws SQLException se a operação JDBC falhar
     */
    private @NonNull DispatchResult dispatchNextInternal(UUID tenantId, UUID deviceId) throws SQLException {
        // O monitor serializa tentativas e recuperação deste emissor; owner.id() exige esse lock.
        synchronized (owner) {
            UUID ownerId = owner.id();
            var endpoint = transport.connect(tenantId, deviceId);
            // O teste de disponibilidade precede a reserva para manter comandos offline na fila.
            if (endpoint.isEmpty())
                return DispatchResult.DEVICE_OFFLINE;

            var session = endpoint.get();
            DeviceCommandDispatchRepository.ClaimedCommand command;
            Optional<DispatchResult> rejection;
            // A reserva e a autorização compartilham a transação; o commit antecede a escrita.
            try (Connection c = dataSource.getConnection()) {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                c.setAutoCommit(false);
                try {
                    var claimed = repository.claimForSession(c, tenantId, deviceId, session.sessionToken());
                    if (claimed.isEmpty()) {
                        c.commit();
                        return DispatchResult.NO_COMMAND;
                    }
                    command = claimed.get();
                    rejection = authorize(c, command, session, ownerId);
                    c.commit();
                } catch (SQLException | RuntimeException error) {
                    c.rollback();
                    throw error;
                }
            }

            // A escrita fica fora da transação JDBC e pode falhar após envio parcial.
            DispatchResult result = Objects.requireNonNull(
                    rejection.isPresent() ? rejection.get() : transmit(command, session));
            LoggerFactory.getLogger(CommandDispatcher.class).info(
                    "Command dispatch tenant={} device={} command={} attempt={} result={}",
                    tenantId,
                    deviceId,
                    command.id(),
                    command.attempts(),
                    result);
            return result;
        }
    }

    /**
     * Ponto de entrada do polling: mede a tentativa e registra métricas de sucesso ou falha antes de devolver ou
     * propagar o resultado.
     *
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @return desfecho da tentativa observado pelo dispatcher
     * @throws SQLException     se a operação JDBC falhar
     * @throws RuntimeException propagada de repositório, transporte ou política
     */
    public @NonNull DispatchResult dispatchNext(
            UUID tenantId,
            UUID deviceId) throws SQLException {

        long startedAt = System.nanoTime();
        DispatchResult result;

        try {
            result = dispatchNextInternal(
                    tenantId,
                    deviceId);
        } catch (SQLException | RuntimeException error) {
            metrics.dispatchFailed();
            throw error;
        }
        metrics.dispatchCompleted(
                result,
                System.nanoTime() - startedAt);
        return result;
    }

    /**
     * Avalia a política do comando e autoriza a transmissão enquanto o bloqueio do cadastro impede uma troca
     * concorrente de sessão. Protocolo e identidade da sessão precisam corresponder ao comando, e o payload precisa ser
     * permitido pelo protocolo.
     *
     * @param c       conexão da transação que mantém a reserva
     * @param command comando reservado nesta tentativa
     * @param session sessão selecionada para a escrita
     * @param ownerId UUID do proprietário que assumirá a transmissão
     * @return resultado da recusa, ou vazio quando a transmissão foi autorizada
     * @throws SQLException se a operação JDBC falhar
     */
    private Optional<DispatchResult> authorize(
            Connection c,
            DeviceCommandDispatchRepository.ClaimedCommand command,
            CommandTransport.Endpoint session,
            UUID ownerId) throws SQLException {
        if (!session.protocol().equals(command.protocol())
                || !FirmwareVersionRequest.permits(command.protocol(), session.identity(), command.payload())) {
            // A recusa é persistida na mesma transação da reserva.
            return Optional.of(
                    repository.finishFailure(c, command, Failure.COMMAND_POLICY_DENIED)
                            ? DispatchResult.DELIVERY_FAILED
                            : DispatchResult.CLAIM_LOST);
        }
        if (!repository.authorizeTransmission(
                c,
                command.tenantId(),
                command.id(),
                command.claimToken(),
                ownerId,
                session.identity())) {
            // Sem alterar a linha, o token já não autoriza esta tentativa.
            return Optional.of(
                    repository.finishFailure(c, command, Failure.AUTHORIZATION_REJECTED)
                            ? DispatchResult.DELIVERY_FAILED
                            : DispatchResult.CLAIM_LOST);
        }
        return Optional.empty();
    }

    /**
     * Escreve somente após o commit da autorização e registra o resultado observado. A falha de escrita é persistida em
     * conexão própria como {@code delivery_unknown}; não há nova tentativa sobre o mesmo token.
     *
     * @param command comando já autorizado para transmissão
     * @param session sessão selecionada para a escrita
     * @return {@link DispatchResult#SENT}, {@link DispatchResult#DELIVERY_UNKNOWN} ou {@link DispatchResult#CLAIM_LOST}
     * @throws SQLException se a operação JDBC de conclusão falhar
     */
    private @NonNull DispatchResult transmit(
            DeviceCommandDispatchRepository.ClaimedCommand command,
            CommandTransport.Endpoint session) throws SQLException {
        UUID tenantId = command.tenantId();
        byte[] payload = command.payload();

        try {
            // A autorização já foi confirmada e a conexão JDBC liberada. Uma falha aqui pode ocorrer após uma escrita
            // parcial. Exceções de runtime não são classificadas: a tentativa fica em transmitting e a recuperação
            // decide.
            writeCommand(session, payload);
        } catch (IOException error) {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(true);
                return repository.finishFailure(c, command, Failure.TCP_WRITE_UNCERTAIN)
                        ? DispatchResult.DELIVERY_UNKNOWN
                        : DispatchResult.CLAIM_LOST;
            } catch (SQLException failure) {
                failure.addSuppressed(error);
                throw failure;
            }
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            return repository.markSent(c, tenantId, command.id(), command.claimToken())
                    ? DispatchResult.SENT
                    : DispatchResult.CLAIM_LOST;
        }
    }

    /**
     * Reconcilia tentativas interrompidas e prazos expirados sob a mesma exclusão usada no envio: recupera o que
     * pertence a este proprietário, classifica reservas e transmissões antigas sem dono e expira comandos na fila e
     * aguardando resposta. Todas as alterações da rodada são confirmadas juntas.
     *
     * @throws SQLException se a operação JDBC falhar
     */
    private void recoverInternal() throws SQLException {
        synchronized (owner) {
            var recovery = new DeviceCommandRecoveryRepository();
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try {
                    // Só o dono do arquivo pode encerrar as transmissões que ele mesmo iniciou.
                    recovery.recoverOwner(c, owner);
                    // O critério por idade não revoga tentativa com proprietário ativo; essa exige o bloqueio do
                    // emissor.
                    recovery.markStaleSendingAsUnknown(c, Duration.ofMinutes(1));
                    recovery.markStaleTransmittingAsUnknown(c, Duration.ofMinutes(1));
                    recovery.expireQueued(c);
                    recovery.expireSent(c);
                    c.commit();
                } catch (SQLException | RuntimeException error) {
                    c.rollback();
                    throw error;
                }
            }
        }
    }

    /**
     * Ponto de entrada do polling de recuperação: mede a rodada e registra falha ou conclusão conforme o desfecho.
     *
     * @throws SQLException     se a recuperação JDBC falhar
     * @throws RuntimeException propagada de repositório ou de {@link DispatchOwner}
     */
    public void recover() throws SQLException {

        long startedAt = System.nanoTime();

        try {
            recoverInternal();
        } catch (SQLException | RuntimeException error) {
            metrics.recoveryFailed();
            throw error;
        } finally {
            metrics.recordRecoveryDuration(
                    System.nanoTime() - startedAt);
        }
        metrics.recoveryCompleted();
    }

    /**
     * Escreve no endpoint e mede a duração da operação, contabilizando falhas de E/S e de runtime antes de propagá-las.
     *
     * @param session endpoint selecionado para a escrita
     * @param payload bytes do comando
     * @throws IOException      se a escrita falhar
     * @throws RuntimeException se o transporte falhar antes de concluir
     */
    private void writeCommand(
            CommandTransport.Endpoint session,
            byte[] payload) throws IOException {

        long startedAt = System.nanoTime();

        try {
            session.send(payload);
        } catch (IOException | RuntimeException error) {
            metrics.tcpWriteFailed();
            throw error;
        } finally {
            metrics.recordTcpWriteDuration(
                    System.nanoTime() - startedAt);
        }
    }
}
