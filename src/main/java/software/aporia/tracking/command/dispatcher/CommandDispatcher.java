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
 * Coordena a reserva no PostgreSQL e a escrita na sessão TCP. A confirmação de escrita e o resultado incerto são
 * persistidos separadamente.
 */
public final class CommandDispatcher {

    /**
     * Resultado observado pelo dispatcher. SENT indica que a escrita local terminou.
     */
    public enum DispatchResult {
        /** Nenhuma sessão disponível para iniciar o envio. */
        DEVICE_OFFLINE,
        /** A consulta da fila não encontrou uma solicitação elegível. */
        NO_COMMAND,
        /** A escrita local terminou e seu resultado foi registrado. */
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

    private final CommandMetrics metrics;

    /**
     * Vincula a fila e o transporte ao proprietário responsável pelas tentativas deste emissor.
     *
     * @param dataSource fonte de conexões JDBC para a operação
     * @param repository repositório que controla a reserva e suas transições
     * @param transport  canal usado para alcançar a sessão do dispositivo
     * @param owner      proprietário do envio ou seu identificador persistido
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
     * Tenta enviar um comando do dispositivo. Quando ele está offline, a fila permanece disponível para a próxima
     * conexão.
     *
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @return desfecho da tentativa observado pelo dispatcher
     * @throws SQLException se a operação JDBC falhar
     */
    private @NonNull DispatchResult dispatchNextInternal(UUID tenantId, UUID deviceId) throws SQLException {
        synchronized (owner) {
            UUID ownerId = owner.id();
            var endpoint = transport.connect(tenantId, deviceId);
            // O teste de disponibilidade precede a reserva para manter comandos offline na fila.
            if (endpoint.isEmpty())
                return DispatchResult.DEVICE_OFFLINE;

            var session = endpoint.get();
            DeviceCommandDispatchRepository.ClaimedCommand command;
            Optional<DispatchResult> rejection;
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
     * Avalia o comando enquanto o bloqueio do cadastro impede uma troca concorrente de sessão.
     */
    private Optional<DispatchResult> authorize(
            Connection c,
            DeviceCommandDispatchRepository.ClaimedCommand command,
            CommandTransport.Endpoint session,
            UUID ownerId) throws SQLException {
        if (!session.protocol().equals(command.protocol())
                || !FirmwareVersionRequest.permits(command.protocol(), session.identity(), command.payload())) {
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
            return Optional.of(
                    repository.finishFailure(c, command, Failure.AUTHORIZATION_REJECTED)
                            ? DispatchResult.DELIVERY_FAILED
                            : DispatchResult.CLAIM_LOST);
        }
        return Optional.empty();
    }

    /**
     * Escreve somente após o commit da autorização e registra o resultado observado.
     */
    private @NonNull DispatchResult transmit(
            DeviceCommandDispatchRepository.ClaimedCommand command,
            CommandTransport.Endpoint session) throws SQLException {
        UUID tenantId = command.tenantId();
        byte[] payload = command.payload();

        try {
            // A autorização já foi confirmada e a conexão JDBC liberada. Uma falha aqui pode ocorrer após uma escrita
            // parcial.
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
     * Reconcilia tentativas interrompidas e prazos expirados sob a mesma exclusão usada no envio.
     *
     * @throws SQLException se a operação JDBC falhar
     */
    private void recoverInternal() throws SQLException {
        synchronized (owner) {
            var recovery = new DeviceCommandRecoveryRepository();
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try {
                    recovery.recoverOwner(c, owner);
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
