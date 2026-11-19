package software.aporia.tracking.storage;

import org.flywaydb.core.Flyway;

public class DatabaseMigration {
    // This class is intentionally left empty. It serves as a placeholder for database migration logic.

    public static void main(String[] args) {
        String url = System.getenv().getOrDefault(
                "TRACKING_DB_URL",
                "jdbc:postgresql://localhost:55432/aporia_tracking_v2"
        );

        String username = System.getenv().getOrDefault(
                "TRACKING_DB_USER",
                "tracking"
        );
        String password = System.getenv("TRACKING_DB_PASSWORD");

        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "TRACKING_DB_PASSWORD is required"
            );
        }

        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .load();

        var result = flyway.migrate();

        System.out.println(
                "Migrations executed: "
                + result.migrationsExecuted
        );
    }
}
