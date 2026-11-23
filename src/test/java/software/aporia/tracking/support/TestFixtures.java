package software.aporia.tracking.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

/** Cria os registros de um tenant de teste e remove os dependentes em ordem compatível com as chaves estrangeiras. */
public final class TestFixtures implements AutoCloseable {

    private final DataSource dataSource;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    private final String deviceIdentity;

    public TestFixtures(DataSource dataSource) throws SQLException {
        this(dataSource, Long.toString(java.util.concurrent.ThreadLocalRandom.current()
                .nextLong(1_000_000_000L, 10_000_000_000L)));
    }

    public TestFixtures(
            DataSource dataSource,
            String deviceIdentity
    ) throws SQLException {

        this.dataSource = dataSource;
        this.deviceIdentity = deviceIdentity;

        create();
    }

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

    public UUID tenantId() {
        return tenantId;
    }

    public UUID deviceId() {
        return deviceId;
    }

    public UUID assetId() {
        return assetId;
    }

    public String deviceIdentity() {
        return deviceIdentity;
    }

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
