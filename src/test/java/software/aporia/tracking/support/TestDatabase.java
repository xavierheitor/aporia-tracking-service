
package software.aporia.tracking.support;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Fornece conexões para um schema isolado por JVM e verifica o banco de testes antes de aplicar as migrations. */
public final class TestDatabase {

    /** URL do schema exclusivo reutilizada pelas conexões da JVM de testes. */
    private static String isolatedUrl;

    /**
     * Restringe a criação de conexões aos métodos de fábrica do suporte de testes.
     */
    private TestDatabase() {
    }

    /**
     * Confere o endereço de testes e configura o pool para o schema isolado.
     *
     * @return pool de conexões configurado
     */
    public static HikariDataSource createDataSource() {

        String url = System.getenv("TRACKING_TEST_DB_URL");
        String user = System.getenv("TRACKING_TEST_DB_USER");
        String password = System.getenv("TRACKING_TEST_DB_PASSWORD");

        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                "TRACKING_TEST_DB_URL is required"
            );
        }

        if (!url.endsWith("/aporia_tracking_test")) {
            throw new IllegalStateException(
                "Integration tests must use aporia_tracking_test"
            );
        }

        if (user == null || user.isBlank()) {
            throw new IllegalStateException(
                "TRACKING_TEST_DB_USER is required"
            );
        }

        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                "TRACKING_TEST_DB_PASSWORD is required"
            );
        }

        HikariConfig config = new HikariConfig();

        config.setJdbcUrl(isolate(url, user, password));
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setPoolName("tracking-test-pool");

        return new HikariDataSource(config);
    }

    /**
     * Verifica banco e usuário, cria um schema por JVM e agenda sua remoção ao encerrar.
     *
     * @param url URL JDBC do banco de testes
     * @param user usuário da conexão de teste
     * @param password credencial da conexão de teste
     * @return URL JDBC com o schema reservado à JVM de testes
     */
    private static synchronized String isolate(String url, String user, String password) {
        if (isolatedUrl != null) return isolatedUrl;
        // O schema exclusivo da JVM isola a recuperação global e as identidades fixas usadas nos exemplos.
        String schema = "tracking_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(url, user, password); var s = c.createStatement()) {
            try (var row = s.executeQuery("SELECT current_database(), current_user")) {
                row.next();
                if (!"aporia_tracking_test".equals(row.getString(1)) || !user.equals(row.getString(2))) {
                    throw new IllegalStateException("UNEXPECTED_TEST_DATABASE");
                }
            }
            s.execute("CREATE SCHEMA " + schema);
        } catch (SQLException e) {
            throw new IllegalStateException("TEST_SCHEMA_SETUP_FAILED", e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (var c = DriverManager.getConnection(url, user, password); var s = c.createStatement()) {
                s.execute("DROP SCHEMA " + schema + " CASCADE");
            } catch (SQLException e) {
                System.err.println("Test schema cleanup failed for " + schema + ", SQLState=" + e.getSQLState());
            }
        }));
        String jdbc = url + "?currentSchema=" + schema + ",public";
        Flyway.configure().dataSource(jdbc, user, password).defaultSchema(schema).load().migrate();
        isolatedUrl = jdbc;
        return jdbc;
    }
}
