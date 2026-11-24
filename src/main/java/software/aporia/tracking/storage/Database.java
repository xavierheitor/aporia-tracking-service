package software.aporia.tracking.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Configura o pool JDBC usado pelos processos a partir das variáveis de ambiente do serviço. */
public final class Database{

    /**
     * Concentra a criação do pool JDBC no método de fábrica.
     */
    private Database() {
    }

    /**
     * Cria o pool JDBC com as credenciais e o endereço fornecidos pelo ambiente.
     *
     * @return pool de conexões configurado
     */
    public static HikariDataSource createDataSource(){
       String url = System.getenv().getOrDefault(
            "TRACKING_DB_URL",
            "jdbc:postgresql://localhost:55432/aporia_tracking_v2"
        );

        String username = System.getenv("TRACKING_DB_USER");
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("TRACKING_DB_USER is required");
        }

        String password = System.getenv("TRACKING_DB_PASSWORD");

        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                "TRACKING_DB_PASSWORD is required"
            );
        }

        HikariConfig config = new HikariConfig();

        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10_000);
        config.setIdleTimeout(300_000);
        config.setMaxLifetime(1_800_000);
        config.setPoolName("aporia-tracking-pool");

        return new HikariDataSource(config);
    }
}