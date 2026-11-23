package software.aporia.tracking.command.dispatcher;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import software.aporia.tracking.storage.DeviceCommandDispatchRepository;

public final class CommandDispatcher {

    public enum DispatchResult {
        DEVICE_OFFLINE,
        NO_COMMAND,
        SENT,
        DELIVERY_UNKNOWN,
        CLAIM_LOST
    }

    private final DataSource dataSource;
    private final DeviceCommandDispatchRepository repository;
    private final CommandTransport transport;

    public CommandDispatcher(
            DataSource dataSource,
            DeviceCommandDispatchRepository repository,
            CommandTransport transport
    ) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.repository = Objects.requireNonNull(repository);
        this.transport = Objects.requireNonNull(transport);
    }

    public DispatchResult dispatchNext(
            UUID tenantId,
            UUID deviceId
    ) throws SQLException {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);

        if (!transport.isConnected(tenantId, deviceId)) {
            return DispatchResult.DEVICE_OFFLINE;
        }

        DeviceCommandDispatchRepository.ClaimedCommand command;

        // Fase 1: reservar e confirmar a transação.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try {
                var claimed = repository.claimNextForDevice(
                        connection,
                        tenantId,
                        deviceId
                );

                if (claimed.isEmpty()) {
                    connection.commit();
                    return DispatchResult.NO_COMMAND;
                }

                command = claimed.get();
                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }

        // Fase 2: autorizar a transmissão.
        try (Connection connection = dataSource.getConnection()) {
            boolean authorized = repository.authorizeTransmission(
                    connection,
                    command.tenantId(),
                    command.id(),
                    command.claimToken()
            );

            if (!authorized) {
                return DispatchResult.CLAIM_LOST;
            }
        }

        // Fase 3: executar a escrita TCP.
        try {
            transport.send(
                    command.tenantId(),
                    command.deviceId(),
                    command.payload()
            );
        } catch (IOException e) {
            // Não sabemos se algum byte chegou ao dispositivo.
            // A recuperação posterior classificará a tentativa
            // como delivery_unknown.
            return DispatchResult.DELIVERY_UNKNOWN;
        }

        // Fase 4: registrar a escrita concluída.
        try (Connection connection = dataSource.getConnection()) {
            boolean updated = repository.markSent(
                    connection,
                    command.tenantId(),
                    command.id(),
                    command.claimToken()
            );

            return updated
                    ? DispatchResult.SENT
                    : DispatchResult.CLAIM_LOST;
        }
    }
}
