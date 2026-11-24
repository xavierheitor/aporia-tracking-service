package software.aporia.tracking.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import software.aporia.tracking.storage.command.DeviceCommandDispatchRepository;

/** Monta comandos históricos ou inválidos sem expor atalhos na persistência de produção. */
public final class CommandTestFixtures {
    private CommandTestFixtures() {}

    /** Cria explicitamente um comando sem auditoria para os ensaios de estado e negação. */
    public static UUID enqueue(Connection connection, UUID tenant, UUID device, String protocol,
                               byte[] payload, Instant expiresAt) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var query = connection.prepareStatement("""
                INSERT INTO device_commands(id,tenant_id,device_id,protocol,payload,expires_at)
                VALUES (?,?,?,?,?,?)
                """)) {
            query.setObject(1, id);
            query.setObject(2, tenant);
            query.setObject(3, device);
            query.setString(4, protocol);
            query.setBytes(5, payload);
            query.setObject(6, expiresAt.atOffset(ZoneOffset.UTC));
            query.executeUpdate();
        }
        return id;
    }

    /** Exercita a reserva real com o tenant, dispositivo e token publicados pela fixture. */
    public static Optional<DeviceCommandDispatchRepository.ClaimedCommand> claim(
            DeviceCommandDispatchRepository repository, Connection connection, TestFixtures fixture) throws SQLException {
        return repository.claimForSession(connection, fixture.tenantId(), fixture.deviceId(), fixture.sessionToken());
    }
    /** Consulta o estado persistido para as asserções, sem criar API de leitura usada só por testes. */
    public static Optional<String> findStatus(Connection connection, UUID tenant, UUID id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT status FROM device_commands WHERE tenant_id=? AND id=?")) {
            query.setObject(1, tenant);
            query.setObject(2, id);
            try (var row = query.executeQuery()) {
                return row.next() ? Optional.of(row.getString(1)) : Optional.empty();
            }
        }
    }

}
