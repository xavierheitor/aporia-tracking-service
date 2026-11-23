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
