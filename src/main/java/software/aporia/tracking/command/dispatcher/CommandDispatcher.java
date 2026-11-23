package software.aporia.tracking.command.dispatcher;

import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import software.aporia.tracking.protocol.suntech.FirmwareVersionRequest;
import software.aporia.tracking.storage.command.DeviceCommandDispatchRepository;
import software.aporia.tracking.storage.command.DeviceCommandRecoveryRepository;
import software.aporia.tracking.storage.command.DeviceCommandDispatchRepository.Failure;

/**
 * Coordena a reserva no PostgreSQL e a escrita na sessão TCP. A confirmação de escrita e o
 * resultado incerto são persistidos separadamente.
 */
public final class CommandDispatcher {

    /** Resultado observado pelo dispatcher. SENT indica que a escrita local terminou. */
    public enum DispatchResult {
        DEVICE_OFFLINE, NO_COMMAND, SENT, DELIVERY_FAILED, DELIVERY_UNKNOWN, CLAIM_LOST
    }

    private final DataSource dataSource;
    private final DeviceCommandDispatchRepository repository;
    private final CommandTransport transport;
    private final DispatchOwner owner;

    /** Vincula a fila e o transporte ao proprietário responsável pelas tentativas deste emissor. */
    public CommandDispatcher(DataSource dataSource, DeviceCommandDispatchRepository repository,
                             CommandTransport transport, DispatchOwner owner) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.repository = Objects.requireNonNull(repository);
        this.transport = Objects.requireNonNull(transport);
        this.owner = Objects.requireNonNull(owner);
    }

    /**
     * Tenta enviar um comando do dispositivo. Quando ele está offline, a fila permanece
     * disponível para a próxima conexão.
     */
    public DispatchResult dispatchNext(UUID tenantId, UUID deviceId) throws SQLException {
        synchronized (owner) {
            UUID ownerId = owner.id();
            var endpoint = transport.connect(tenantId, deviceId);
            if (endpoint.isEmpty()) return DispatchResult.DEVICE_OFFLINE;

            var session = endpoint.get();
            DeviceCommandDispatchRepository.ClaimedCommand command;
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try {
                    var claimed = repository.claimNextForDevice(c, tenantId, deviceId);
                    c.commit();
                    if (claimed.isEmpty()) return DispatchResult.NO_COMMAND;
                    command = claimed.get();
                } catch (SQLException | RuntimeException error) {
                    c.rollback();
                    throw error;
                }
            }

            DispatchResult result = transmit(command, session, ownerId);
            LoggerFactory.getLogger(CommandDispatcher.class).info(
                    "Command dispatch tenant={} device={} command={} attempt={} result={}",
                    tenantId, deviceId, command.id(), command.attempts(), result);
            return result;
        }
    }

    private DispatchResult transmit(DeviceCommandDispatchRepository.ClaimedCommand command,
                                    CommandTransport.Endpoint session, UUID ownerId) throws SQLException {
        UUID tenantId = command.tenantId();
        byte[] payload = command.payload();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            if (!session.protocol().equals(command.protocol())
                    || !FirmwareVersionRequest.permits(command.protocol(), session.identity(), payload)) {
                return repository.finishFailure(c, command, Failure.COMMAND_POLICY_DENIED)
                        ? DispatchResult.DELIVERY_FAILED : DispatchResult.CLAIM_LOST;
            }
            if (!repository.authorizeTransmission(c, tenantId, command.id(), command.claimToken(), ownerId, session.identity())) {
                return repository.finishFailure(c, command, Failure.AUTHORIZATION_REJECTED)
                        ? DispatchResult.DELIVERY_FAILED : DispatchResult.CLAIM_LOST;
            }
        }

        try {
            // A autorização já foi confirmada e a conexão JDBC liberada. Uma falha aqui pode ocorrer após uma escrita parcial.
            session.send(payload);
        } catch (IOException error) {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(true);
                return repository.finishFailure(c, command, Failure.TCP_WRITE_UNCERTAIN)
                        ? DispatchResult.DELIVERY_UNKNOWN : DispatchResult.CLAIM_LOST;
            } catch (SQLException failure) {
                failure.addSuppressed(error);
                throw failure;
            }
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            return repository.markSent(c, tenantId, command.id(), command.claimToken())
                    ? DispatchResult.SENT : DispatchResult.CLAIM_LOST;
        }
    }

    /** Reconcilia tentativas interrompidas e prazos expirados sob a mesma exclusão usada no envio. */
    public void recover() throws SQLException {
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
}
