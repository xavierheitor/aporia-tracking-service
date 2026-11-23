package software.aporia.tracking.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

/** Cria os registros de um tenant de teste e remove os dependentes em ordem compatível com as chaves estrangeiras. */
public final class TestFixtures implements AutoCloseable {

    /** Fonte de conexões JDBC usadas pela instância. */
    private final DataSource dataSource;

    /** Tenant proprietário do cadastro do equipamento. */
    private final UUID tenantId = UUID.randomUUID();
    /** Identificador interno do equipamento cadastrado. */
    private final UUID deviceId = UUID.randomUUID();
    /** Ativo associado ao equipamento da fixture. */
    private final UUID assetId = UUID.randomUUID();

    /** Identidade que acompanha os quadros do equipamento. */
    private final String deviceIdentity;

    /**
     * Cria os registros de teste com a identidade atribuída pelo construtor.
     *
     * @param dataSource fonte de conexões JDBC para a operação
     * @throws SQLException se a operação JDBC falhar
     */
    public TestFixtures(DataSource dataSource) throws SQLException {
        this(dataSource, Long.toString(java.util.concurrent.ThreadLocalRandom.current()
                .nextLong(1_000_000_000L, 10_000_000_000L)));
    }

    /**
     * Prepara o tenant, o equipamento e a associação usados pelo cenário.
     *
     * @param dataSource fonte de conexões JDBC para a operação
     * @param deviceIdentity identidade declarada pelo rastreador no protocolo
     * @throws SQLException se a operação JDBC falhar
     */
    public TestFixtures(
            DataSource dataSource,
            String deviceIdentity
    ) throws SQLException {

        this.dataSource = dataSource;
        this.deviceIdentity = deviceIdentity;

        create();
    }

    /**
     * Insere os registros relacionados dentro de uma transação.
     *
     * @throws SQLException se a operação JDBC falhar
     */
    private void create() throws SQLException {

        try (Connection connection
                = dataSource.getConnection()) {

            connection.setAutoCommit(false);

            try {
                execute(
                        connection,
                        "INSERT INTO tenants (id, name) VALUES (?, ?)",
                        tenantId,
                        "Integration Test"
                );

                execute(
                        connection,
                        """
                    INSERT INTO devices (
                        id, tenant_id, protocol, identity
                    )
                    VALUES (?, ?, ?, ?)
                    """,
                        deviceId,
                        tenantId,
                        "suntech",
                        deviceIdentity
                );

                execute(
                        connection,
                        """
                    INSERT INTO assets (
                        id, tenant_id, name
                    )
                    VALUES (?, ?, ?)
                    """,
                        assetId,
                        tenantId,
                        "Test Vehicle"
                );

                execute(
                        connection,
                        """
                    INSERT INTO assignments (
                        id, tenant_id, device_id,
                        asset_id, starts_at
                    )
                    VALUES (?, ?, ?, ?, ?)
                    """,
                        UUID.randomUUID(),
                        tenantId,
                        deviceId,
                        assetId,
                        java.sql.Timestamp.from(
                                java.time.Instant.parse(
                                        "2026-01-01T00:00:00Z"
                                )
                        )
                );

                connection.commit();

            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Vincula os parâmetros à instrução da fixture na conexão recebida.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param sql instrução SQL executada na conexão de teste
     * @param parameters valores vinculados aos parâmetros SQL
     * @throws SQLException se a operação JDBC falhar
     */
    private void execute(
            Connection connection,
            String sql,
            Object... parameters
    ) throws SQLException {

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }

            statement.executeUpdate();
        }
    }

    /**
     * Fornece o tenant proprietário dos registros da fixture.
     *
     * @return UUID do tenant
     */
    public UUID tenantId() {
        return tenantId;
    }

    /**
     * Fornece o equipamento que participa das consultas do cenário.
     *
     * @return UUID do dispositivo
     */
    public UUID deviceId() {
        return deviceId;
    }

    /**
     * Fornece o ativo associado ao rastreador.
     *
     * @return UUID do ativo
     */
    public UUID assetId() {
        return assetId;
    }

    /**
     * Fornece a identidade usada para construir as mensagens do equipamento.
     *
     * @return identidade do equipamento no protocolo
     */
    public String deviceIdentity() {
        return deviceIdentity;
    }

    /**
     * Remove primeiro os registros dependentes e confirma a limpeza em uma transação.
     *
     * @throws SQLException se a operação JDBC falhar
     */
    @Override
    public void close() throws SQLException {

        try (Connection connection = dataSource.getConnection()) {

            connection.setAutoCommit(false);

            try {

                // 1. Respostas de comandos
                execute(
                        connection,
                        "DELETE FROM device_command_responses WHERE tenant_id = ?",
                        tenantId
                );

                // 2. Eventos de integração
                execute(
                        connection,
                        "DELETE FROM outbox_events WHERE tenant_id = ?",
                        tenantId
                );

                // 3. Últimas posições
                execute(
                        connection,
                        "DELETE FROM last_positions WHERE tenant_id = ?",
                        tenantId
                );

                // 4. Histórico de posições
                execute(
                        connection,
                        "DELETE FROM positions WHERE tenant_id = ?",
                        tenantId
                );

                // 5. Mensagens processadas
                execute(
                        connection,
                        "DELETE FROM messages WHERE tenant_id = ?",
                        tenantId
                );

                // 6. Pacotes recebidos
                execute(
                        connection,
                        "DELETE FROM raw_packets WHERE tenant_id = ?",
                        tenantId
                );

                // 7. Comandos enviados
                execute(
                        connection,
                        "DELETE FROM device_commands WHERE tenant_id = ?",
                        tenantId
                );

                // 8. Associações dispositivo/ativo
                execute(
                        connection,
                        "DELETE FROM assignments WHERE tenant_id = ?",
                        tenantId
                );

                // 9. Ativos
                execute(
                        connection,
                        "DELETE FROM assets WHERE tenant_id = ?",
                        tenantId
                );

                execute(connection, """
                        DELETE FROM device_discoveries WHERE id IN (SELECT id FROM devices WHERE tenant_id=?)
                        """, tenantId);

                // 10. Dispositivos
                execute(
                        connection,
                        "DELETE FROM devices WHERE tenant_id = ?",
                        tenantId
                );

                // 11. Tenant de teste
                execute(
                        connection,
                        "DELETE FROM tenants WHERE id = ?",
                        tenantId
                );

                connection.commit();

            } catch (SQLException | RuntimeException e) {

                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }

                throw e;
            }
        }
    }
}
