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
 *
 * <p>A consulta e o registro de descoberta abrem a própria conexão em autocommit a partir do pool. A
 * identidade é única por protocolo no cadastro, então o tenant é derivado do registro encontrado em vez de
 * informado pelo chamador. O cadastro desabilitado continua visível em {@link Registration}, mas não pode
 * abrir sessão nem receber comandos.</p>
 */
public final class JdbcDeviceLookup {

    /** Fonte de conexões JDBC usadas pela instância. */
    private final DataSource dataSource;

    /**
     * Recebe o pool usado na consulta de cadastro e na atualização da fila de descobertas.
     *
     * @param dataSource fonte de conexões JDBC para a operação
     * @throws NullPointerException se a fonte de conexões for nula
     */
    public JdbcDeviceLookup(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    /**
     * Combina os dados do cadastro com a indicação de habilitação usada na admissão da conexão.
     *
     * <p>O registro é devolvido também quando {@code enabled} é falso, permitindo distinguir cadastro
     * desabilitado de identidade desconhecida.</p>
     *
     * @param device cadastro localizado por protocolo e identidade
     * @param enabled indica se a conexão pode abrir sessão
     */
    public record Registration(RegisteredDevice device, boolean enabled) {}

    /**
     * Distingue cadastro desabilitado de identidade desconhecida antes da descoberta.
     *
     * <p>A consulta não filtra {@code enabled}; o estado é devolvido para que a admissão decida. O par
     * protocolo e identidade é único no cadastro, do qual o tenant é lido. Usa uma conexão nova do pool em
     * autocommit e não participa da transação de ingestão. Falha de JDBC é convertida em {@link
     * IllegalStateException}.</p>
     *
     * @param protocol identificador do protocolo do equipamento
     * @param identity identidade declarada pelo rastreador no protocolo
     * @return cadastro com seu estado de habilitação, se encontrado
     * @throws NullPointerException  se protocolo ou identidade for nulo
     * @throws IllegalStateException se a consulta JDBC falhar
     */
    public Optional<Registration> find(
            String protocol,
            String identity
    ) {
        Objects.requireNonNull(protocol);
        Objects.requireNonNull(identity);

        // O estado habilitado é devolvido, não filtrado: a admissão precisa
        // distinguir cadastro desabilitado de identidade desconhecida.
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
     * Grava o token da sessão admitida no cadastro do dispositivo, em autocommit.
     *
     * <p>O {@code UPDATE} só alcança a linha do tenant, dispositivo, protocolo e identidade informados e
     * exige {@code enabled}; a troca do UUID serializa com a reserva, que bloqueia a mesma linha do
     * dispositivo. Substituir o token não interrompe uma tentativa já autorizada: ela segue protegida pelo
     * token da reserva e pelo índice {@code uq_commands_in_flight}. Zero linhas indica cadastro alterado,
     * desabilitado ou removido concorrentemente.</p>
     *
     * @param device cadastro que recebeu a sessão
     * @param token token da nova sessão
     * @throws NullPointerException  se o cadastro ou o token for nulo
     * @throws IllegalStateException se a atualização não afetar exatamente uma linha
     *                               ou se a operação JDBC falhar
     */
    public void activateSession(RegisteredDevice device, UUID token) {
        try (var c = dataSource.getConnection();
             var update = c.prepareStatement("""
                     UPDATE devices SET command_session_token=?
                     WHERE tenant_id=? AND id=? AND protocol=? AND identity=? AND enabled
                     """)) {
            c.setAutoCommit(true);
            // A atualização repete o escopo completo e exige cadastro habilitado;
            // autocommit confirma a troca imediatamente.
            update.setObject(1, Objects.requireNonNull(token));
            update.setObject(2, device.tenantId());
            update.setObject(3, device.deviceId());
            update.setString(4, device.protocol());
            update.setString(5, device.identity());
            if (update.executeUpdate() != 1) throw new IllegalStateException("SESSION_ADMISSION_DEFERRED");
        } catch (SQLException e) {
            throw new IllegalStateException("SESSION_ACTIVATION_FAILED", e);
        }
    }

    /**
     * Insere a primeira observação ou atualiza a pendência do mesmo protocolo e identidade
     * enquanto aguarda aprovação.
     *
     * <p>Não recebe tenant: a descoberta pertence à instalação até ser aprovada. Se já existir cadastro para
     * o par, nada é inserido nem atualizado. Em reconexões de uma pendência, incrementa {@code observations},
     * avança {@code last_seen_at} com o maior instante e atualiza formato e modelo; a cláusula {@code
     * approved_at IS NULL} impede alterar uma descoberta já aprovada. Usa conexão própria em autocommit.</p>
     *
     * @param protocol identificador do protocolo do equipamento
     * @param identity identidade declarada pelo rastreador no protocolo
     * @param description marca, tipo e formato extraídos pelo adaptador do quadro validado
     * @throws NullPointerException  se protocolo, identidade ou descrição for nulo
     * @throws IllegalStateException se a gravação JDBC falhar
     */
    public void recordDiscovery(String protocol, String identity,
                                DeviceDescription description) {
        // Uma reconexão incrementa a mesma pendência. A aprovação encerra as atualizações desse registro.
        // Se o par já existe no cadastro, o SELECT não produz linha e a descoberta é ignorada.
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
