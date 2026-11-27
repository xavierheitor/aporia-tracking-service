package software.aporia.tracking.storage.device;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.protocol.DeviceDescription;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercita a consulta de equipamentos habilitados e a disputa entre tenants pela aprovação de uma
 * descoberta.
 *
 * <p>Os cenários de consulta inserem tenant e dispositivo diretamente e removem no bloco
 * {@code finally}. O cenário concorrente usa {@link TestFixtures} para dois tenants e
 * {@code device_discoveries}, cuja aprovação cria o dispositivo reaproveitando o id da descoberta.
 * O schema é isolado por JVM; as identidades fixas evitam colisão com o índice único
 * {@code (protocol, identity)}.</p>
 */
class JdbcDeviceLookupIT {

    /**
     * Abre o pool do schema reservado aos testes de integração.
     *
     * @return pool de conexões configurado
     */
    private HikariDataSource createDataSource() {

        return TestDatabase.createDataSource();
    }

    /**
     * Cenário: tenant ainda sem cadastro. Ação: insere tenant e dispositivo com a identidade e o
     * estado de habilitação informados e confirma os dois INSERTs juntos. Resultado: o cadastro fica
     * visível às consultas; uma falha desfaz a transação e é propagada.
     *
     * @param dataSource fonte de conexões JDBC para a operação
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @param identity identidade declarada pelo rastreador no protocolo
     * @param enabled indica se o equipamento pode conectar
     * @throws SQLException se a operação JDBC falhar
     */
    private void createFixture(
            HikariDataSource dataSource,
            UUID tenantId,
            UUID deviceId,
            String identity,
            boolean enabled
    ) throws SQLException {

        try (Connection connection
                = dataSource.getConnection()) {

            connection.setAutoCommit(false);

            try (
                    PreparedStatement tenant
                    = connection.prepareStatement(
                            """
                                    INSERT INTO tenants (id, name)
                                    VALUES (?, ?)
                                    """
                    ); PreparedStatement device
                    = connection.prepareStatement(
                            """
                                    INSERT INTO devices
                                        (id, tenant_id, protocol, identity, enabled)
                                    VALUES (?, ?, ?, ?, ?)
                                    """
                    )) {

                        tenant.setObject(1, tenantId);
                        tenant.setString(2, "Test Tenant " + tenantId);
                        tenant.executeUpdate();

                        device.setObject(1, deviceId);
                        device.setObject(2, tenantId);
                        device.setString(3, "suntech");
                        device.setString(4, identity);
                        device.setBoolean(5, enabled);
                        device.executeUpdate();

                        connection.commit();

                    } catch (SQLException | RuntimeException e) {
                        connection.rollback();
                        throw e;
                    }
        }
    }

    /**
     * Remove dispositivo e tenant do cenário na ordem das chaves estrangeiras, em uma transação.
     *
     * <p>A rotina não apaga {@code device_discoveries}: os cenários que a usam não criam registros
     * nessa tabela.</p>
     *
     * @param dataSource fonte de conexões JDBC para a operação
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @throws SQLException se a operação JDBC falhar
     */
    private void deleteFixture(
            HikariDataSource dataSource,
            UUID tenantId,
            UUID deviceId
    ) throws SQLException {

        try (Connection connection
                = dataSource.getConnection()) {

            connection.setAutoCommit(false);

            try (
                    PreparedStatement deleteDevice
                    = connection.prepareStatement(
                            "DELETE FROM devices WHERE id = ?"
                    ); PreparedStatement deleteTenant
                    = connection.prepareStatement(
                            "DELETE FROM tenants WHERE id = ?"
                    )) {

                        deleteDevice.setObject(1, deviceId);
                        deleteDevice.executeUpdate();

                        deleteTenant.setObject(1, tenantId);
                        deleteTenant.executeUpdate();

                        connection.commit();

                    } catch (SQLException | RuntimeException e) {
                        connection.rollback();
                        throw e;
                    }
        }
    }

    /**
     * Cenário: uma descoberta pendente do protocolo Teltonika e dois tenants disputando a aprovação.
     * Ação: duas threads chamam {@code approve_device_discovery} sob a mesma barreira. Resultado:
     * exatamente uma aprovação vence e a outra é classificada como
     * {@code DISCOVERY_ALREADY_APPROVED}; a consulta habilitada devolve o tenant vencedor.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void concurrentApprovalsAssignDiscoveryToExactlyOneTenant() throws Exception {
        try (var db = createDataSource(); var first = new TestFixtures(db); var second = new TestFixtures(db);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Prefixo do protocolo Teltonika para não colidir com as identidades Suntech da fixture.
            String identity = "35000" + first.deviceIdentity();
            var lookup = new JdbcDeviceLookup(db);
            lookup.recordDiscovery("teltonika", identity, new DeviceDescription("Teltonika", "gps_tracker", "Codec 8", null));
            var barrier = new CyclicBarrier(2);
            // As aprovações partem juntas para disputar o FOR UPDATE da função.
            var a = executor.submit(() -> approve(db, identity, first.tenantId(), barrier));
            var b = executor.submit(() -> approve(db, identity, second.tenantId(), barrier));
            boolean firstApproved = a.get(10, TimeUnit.SECONDS);
            // Apenas um dos dois retornos pode ser verdadeiro.
            assertEquals(!firstApproved, b.get(10, TimeUnit.SECONDS));
            var registered = lookup.find("teltonika", identity).filter(registration -> registration.enabled()).map(registration -> registration.device()).orElseThrow();
            assertEquals(firstApproved ? first.tenantId() : second.tenantId(), registered.tenantId());
        }
    }

    /**
     * Cenário: uma tentativa de aprovação concorrente. Ação: prepara o SELECT da função, espera a
     * barreira e executa. Resultado: verdadeiro quando este operador aprova; falso quando o banco
     * sinaliza {@code DISCOVERY_ALREADY_APPROVED}; outras falhas SQL são propagadas.
     *
     * @param db fonte de conexões JDBC para a operação
     * @param identity identidade declarada pelo rastreador no protocolo
     * @param tenant identificador do tenant que delimita a operação
     * @param barrier barreira que sincroniza as aprovações concorrentes
     * @return verdadeiro quando este operador conseguiu aprovar a descoberta
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private boolean approve(HikariDataSource db, String identity, UUID tenant, CyclicBarrier barrier) throws Exception {
        try (var c = db.getConnection(); var query = c.prepareStatement("""
                SELECT approve_device_discovery(id, ?) FROM device_discoveries WHERE protocol='teltonika' AND identity=?
                """)) {
            query.setObject(1, tenant);
            query.setString(2, identity);
            barrier.await(5, TimeUnit.SECONDS);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                return true;
            } catch (SQLException conflict) {
                // A função bloqueia a descoberta; o perdedor a encontra já aprovada por outro tenant.
                if (conflict.getMessage().contains("DISCOVERY_ALREADY_APPROVED")) return false;
                throw conflict;
            }
        }
    }

    /**
     * Cenário: dispositivo Suntech habilitado, com identidade fixa. Ação: consulta pelo par protocolo
     * e identidade e filtra habilitação. Resultado: tenant, dispositivo, protocolo e identidade
     * coincidem com o cadastro; a fixture é removida no {@code finally}.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldFindEnabledDevice() throws Exception {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        String identity = "9876543210";

        try (HikariDataSource dataSource = createDataSource()) {

            createFixture(
                    dataSource,
                    tenantId,
                    deviceId,
                    identity,
                    true
            );

            try {

                JdbcDeviceLookup lookup
                        = new JdbcDeviceLookup(dataSource);

                RegisteredDevice device
                        = lookup.find("suntech", identity
                        ).filter(registration -> registration.enabled()).map(registration -> registration.device()).orElseThrow();

                assertEquals(tenantId, device.tenantId());
                assertEquals(deviceId, device.deviceId());
                assertEquals("suntech", device.protocol());
                assertEquals(identity, device.identity());

            } finally {
                deleteFixture(dataSource, tenantId, deviceId);
            }
        }
    }

    /**
     * Cenário: dispositivo Suntech desabilitado. Ação: consulta e filtra habilitação. Resultado:
     * vazio; a busca encontra o cadastro, mas a sessão não pode ser admitida.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldNotFindDisabledDevice() throws Exception {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        String identity = "9876543211";

        try (HikariDataSource dataSource = createDataSource()) {

            createFixture(
                    dataSource,
                    tenantId,
                    deviceId,
                    identity,
                    false
            );

            try {

                JdbcDeviceLookup lookup
                        = new JdbcDeviceLookup(dataSource);

                assertTrue(
                        lookup.find("suntech", identity
                        ).filter(registration -> registration.enabled()).map(registration -> registration.device()).isEmpty()
                );

            } finally {
                deleteFixture(dataSource, tenantId, deviceId);
            }
        }
    }

    /**
     * Cenário: identidade sem cadastro no schema. Ação: consulta Suntech de um número fixo. Resultado:
     * vazio, sem criar registros e sem limpeza.
     */
    @Test
    void shouldRejectUnknownDevice() {

        try (HikariDataSource dataSource = createDataSource()) {

            JdbcDeviceLookup lookup
                    = new JdbcDeviceLookup(dataSource);

            assertTrue(
                    lookup.find("suntech", "0000000000"
                    ).filter(registration -> registration.enabled()).map(registration -> registration.device()).isEmpty()
            );
        }
    }

    /**
     * Cenário: dispositivo Suntech habilitado. Ação: consulta a mesma identidade como Teltonika.
     * Resultado: vazio; a busca sempre combina protocolo e identidade e a fixture é removida no
     * {@code finally}.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectWrongProtocol() throws Exception {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        String identity = "9876543212";

        try (HikariDataSource dataSource = createDataSource()) {

            createFixture(
                    dataSource,
                    tenantId,
                    deviceId,
                    identity,
                    true
            );

            try {

                JdbcDeviceLookup lookup
                        = new JdbcDeviceLookup(dataSource);

                assertTrue(
                        lookup.find("teltonika", identity
                        ).filter(registration -> registration.enabled()).map(registration -> registration.device()).isEmpty()
                );

            } finally {
                deleteFixture(dataSource, tenantId, deviceId);
            }
        }
    }
}
