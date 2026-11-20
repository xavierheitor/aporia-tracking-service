
package software.aporia.tracking.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class TestDatabase {

    private TestDatabase() {
    }

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

        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setPoolName("tracking-test-pool");

        return new HikariDataSource(config);
    }
}
