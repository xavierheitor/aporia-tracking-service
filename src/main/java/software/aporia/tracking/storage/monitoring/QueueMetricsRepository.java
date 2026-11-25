package software.aporia.tracking.storage.monitoring;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;

/**
 * Consulta exclusivamente indicadores agregados das filas persistidas no PostgreSQL.
 */
public final class QueueMetricsRepository {

    private static final String PACKETS_SQL = """
            SELECT
                COUNT(*) AS pending,
                COUNT(*) FILTER (
                    WHERE available_at <= NOW()
                ) AS eligible,
                COUNT(*) FILTER (
                    WHERE available_at > NOW()
                      AND attempts > 0
                ) AS retry_waiting,
                COALESCE(
                    GREATEST(
                        0,
                        EXTRACT(EPOCH FROM
                            NOW() - MIN(received_at))
                    ),
                    0
                ) AS oldest_age
            FROM raw_packets
            WHERE status = 'pending'
            """;

    private static final String COMMANDS_SQL = """
            SELECT status, COUNT(*) AS total
            FROM device_commands
            GROUP BY status
            """;

    private static final String OUTBOX_SQL = """
            SELECT
                COUNT(*) AS pending,
                COALESCE(
                    GREATEST(
                        0,
                        EXTRACT(EPOCH FROM
                            NOW() - MIN(created_at))
                    ),
                    0
                ) AS oldest_age
            FROM outbox_events
            WHERE published_at IS NULL
            """;

    private final DataSource dataSource;
    private final int queryTimeoutSeconds;

    public QueueMetricsRepository(
            DataSource dataSource,
            int queryTimeoutSeconds) {

        if (queryTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "Query timeout must be positive");
        }

        this.dataSource = Objects.requireNonNull(dataSource);

        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public @NonNull QueueSnapshot collect() throws SQLException {

        try (Connection connection = dataSource.getConnection()) {

            connection.setReadOnly(true);

            connection.setTransactionIsolation(
                    Connection.TRANSACTION_REPEATABLE_READ);

            connection.setAutoCommit(false);

            try {
                QueueSnapshot snapshot = readSnapshot(connection);

                connection.commit();

                return snapshot;

            } catch (SQLException | RuntimeException error) {

                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }

                throw error;
            }
        }
    }

    private @NonNull QueueSnapshot readSnapshot(
            Connection connection) throws SQLException {

        long pendingPackets;
        long eligiblePackets;
        long retryWaitingPackets;
        double oldestPacketAge;

        try (var statement = connection.prepareStatement(PACKETS_SQL)) {

            statement.setQueryTimeout(queryTimeoutSeconds);

            try (var result = statement.executeQuery()) {

                if (!result.next()) {
                    throw new SQLException(
                            "Packet metrics result missing");
                }

                pendingPackets = result.getLong("pending");

                eligiblePackets = result.getLong("eligible");

                retryWaitingPackets = result.getLong("retry_waiting");

                oldestPacketAge = result.getDouble("oldest_age");
            }
        }

        Map<String, Long> commands = new HashMap<>();

        try (var statement = connection.prepareStatement(COMMANDS_SQL)) {

            statement.setQueryTimeout(queryTimeoutSeconds);

            try (var result = statement.executeQuery()) {

                while (result.next()) {
                    commands.put(
                            result.getString("status"),
                            result.getLong("total"));
                }
            }
        }

        long pendingOutbox;
        double oldestOutboxAge;

        try (var statement = connection.prepareStatement(OUTBOX_SQL)) {

            statement.setQueryTimeout(queryTimeoutSeconds);

            try (var result = statement.executeQuery()) {

                if (!result.next()) {
                    throw new SQLException(
                            "Outbox metrics result missing");
                }

                pendingOutbox = result.getLong("pending");

                oldestOutboxAge = result.getDouble("oldest_age");
            }
        }

        return new QueueSnapshot(
                pendingPackets,
                eligiblePackets,
                retryWaitingPackets,
                oldestPacketAge,
                commands,
                pendingOutbox,
                oldestOutboxAge);
    }
}
