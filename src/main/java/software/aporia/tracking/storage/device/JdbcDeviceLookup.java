package software.aporia.tracking.storage.device;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

import software.aporia.tracking.protocol.DeviceDescription;

/**
 * Consulta o cadastro por protocolo e identidade e atualiza as observações de equipamentos
 * pendentes de autorização.
 */
public final class JdbcDeviceLookup {

    /** Fonte de conexões JDBC usadas pela instância. */
    private final DataSource dataSource;

    /**
     * Recebe o pool usado na consulta de cadastro e na atualização da fila de descobertas.
     *
     * @param dataSource fonte de conexões JDBC para a operação
     */
    public JdbcDeviceLookup(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    /**
     * Combina os dados do cadastro com a indicação de habilitação usada na admissão da conexão.
     * @param device cadastro localizado por protocolo e identidade
     * @param enabled indica se a conexão pode abrir sessão
     */
    public record Registration(RegisteredDevice device, boolean enabled) {}

    /**
     * Retorna o dispositivo somente quando o cadastro está habilitado.
     *
     * @param protocol identificador do protocolo do equipamento
     * @param identity identidade declarada pelo rastreador no protocolo
     * @return cadastro habilitado, quando encontrado
     */
    public Optional<RegisteredDevice> findEnabled(String protocol, String identity) {
        return find(protocol, identity).filter(Registration::enabled).map(Registration::device);
    }

    /**
     * Distingue cadastro desabilitado de identidade desconhecida antes da descoberta.
     *
     * @param protocol identificador do protocolo do equipamento
     * @param identity identidade declarada pelo rastreador no protocolo
     * @return cadastro com seu estado de habilitação, se encontrado
     */
    public Optional<Registration> find(
            String protocol,
            String identity
    ) {
        Objects.requireNonNull(protocol);
        Objects.requireNonNull(identity);

        String sql = """
            SELECT id, tenant_id, enabled
            FROM devices
            WHERE protocol = ?
              AND identity = ?
            """;

        try (
                Connection connection = dataSource.getConnection(); PreparedStatement statement
                = connection.prepareStatement(sql)) {
            statement.setString(1, protocol);
            statement.setString(2, identity);

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    return Optional.empty();
                }

                UUID deviceId
                        = result.getObject("id", UUID.class);

                UUID tenantId
                        = result.getObject("tenant_id", UUID.class);

                return Optional.of(new Registration(
                        new RegisteredDevice(tenantId, deviceId, protocol, identity), result.getBoolean("enabled")));
            }

        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DEVICE_LOOKUP_FAILED", e
            );
        }
    }

    /**
     * Troca a sessão autorizada sob o mesmo bloqueio da reserva de comandos. Enquanto uma
     * escrita autorizada estiver em curso, o rastreador poderá tentar novamente na reconexão.
     */
    public void activateSession(RegisteredDevice device, UUID token) {
        try (var c = dataSource.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try (var lock = c.prepareStatement("""
                    SELECT id FROM devices WHERE tenant_id=? AND id=? AND protocol=? AND identity=?
                        AND enabled FOR UPDATE
                    """);
                 var update = c.prepareStatement("""
                    UPDATE devices d SET command_session_token=? WHERE d.tenant_id=? AND d.id=?
                      AND NOT EXISTS (SELECT 1 FROM device_commands c WHERE c.tenant_id=d.tenant_id
                          AND c.device_id=d.id AND c.status='transmitting')
                    """)) {
                lock.setObject(1, device.tenantId());
                lock.setObject(2, device.deviceId());
                lock.setString(3, device.protocol());
                lock.setString(4, device.identity());
                try (var rows = lock.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("SESSION_ADMISSION_DEFERRED");
                }
                // Em READ COMMITTED esta segunda consulta vê a autorização confirmada antes do bloqueio.
                update.setObject(1, Objects.requireNonNull(token));
                update.setObject(2, device.tenantId());
                update.setObject(3, device.deviceId());
                if (update.executeUpdate() != 1) throw new IllegalStateException("SESSION_ADMISSION_DEFERRED");
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SESSION_ACTIVATION_FAILED", e);
        }
    }

    /**
     * Insere a primeira observação ou atualiza a pendência do mesmo protocolo e identidade
     * enquanto aguarda aprovação.
     *
     * @param protocol identificador do protocolo do equipamento
     * @param identity identidade declarada pelo rastreador no protocolo
     * @param description marca, tipo e formato extraídos pelo adaptador do quadro validado
     */
    public void recordDiscovery(String protocol, String identity,
                                DeviceDescription description) {
        // Uma reconexão incrementa a mesma pendência. A aprovação encerra as atualizações desse registro.
        String sql = """
                INSERT INTO device_discoveries AS q
                    (protocol, identity, manufacturer, device_type, wire_format, reported_model)
                SELECT ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (
                    SELECT 1 FROM devices WHERE protocol=? AND identity=?
                )
                ON CONFLICT (protocol, identity) DO UPDATE SET
                    last_seen_at=GREATEST(clock_timestamp(), q.last_seen_at),
                    observations=q.observations+1, wire_format=excluded.wire_format,
                    reported_model=coalesce(excluded.reported_model,q.reported_model)
                WHERE q.approved_at IS NULL
                """;
        try (var c = dataSource.getConnection(); var statement = c.prepareStatement(sql)) {
            statement.setString(1, protocol);
            statement.setString(2, identity);
            statement.setString(3, description.manufacturer());
            statement.setString(4, description.deviceType());
            statement.setString(5, description.wireFormat());
            statement.setString(6, description.reportedModel());
            statement.setString(7, protocol);
            statement.setString(8, identity);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("DEVICE_DISCOVERY_FAILED", e);
        }
    }
}
