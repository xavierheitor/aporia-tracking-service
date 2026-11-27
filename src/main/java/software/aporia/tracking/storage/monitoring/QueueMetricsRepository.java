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
 *
 * <p>Os números são globais: as três consultas não filtram tenant e somam todas as linhas visíveis. A leitura
 * ocorre em uma única transação somente leitura, com isolamento {@code REPEATABLE READ}, para que os três
 * agregados reflitam o mesmo instante lógico, e com timeout por instrução. O repositório não grava nada.</p>
 */
public final class QueueMetricsRepository {

    /** Agregados dos pacotes brutos ainda pendentes, incluindo os elegíveis para nova tentativa. */
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

    /** Contagem de comandos por estado, sem recorte de tenant. */
    private static final String COMMANDS_SQL = """
            SELECT status, COUNT(*) AS total
            FROM device_commands
            GROUP BY status
            """;

    /** Agregados dos eventos de outbox ainda não publicados. */
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

    /** Pool que fornece a conexão somente leitura usada por cada coleta. */
    private final DataSource dataSource;
    /** Timeout, em segundos, aplicado a cada consulta da coleta. */
    private final int queryTimeoutSeconds;

    /**
     * Configura o pool de leitura e o limite de tempo das consultas.
     *
     * @param dataSource          fonte de conexões JDBC para a coleta
     * @param queryTimeoutSeconds timeout positivo, em segundos, por instrução
     * @throws IllegalArgumentException se o timeout não for positivo
     * @throws NullPointerException     se a fonte de conexões for nula
     */
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

    /**
     * Coleta os três agregados em uma transação somente leitura e devolve a fotografia.
     *
     * <p>Configura {@code readOnly}, {@code REPEATABLE READ} e transação explícita e confirma somente depois
     * de ler tudo. Qualquer falha, inclusive de runtime, desfaz a transação antes de propagar; uma falha de
     * rollback é anexada como suprimida.</p>
     *
     * @return fotografia consistente das filas no instante da transação
     * @throws SQLException se a leitura, o commit ou a configuração da conexão falhar
     */
    public @NonNull QueueSnapshot collect() throws SQLException {

        try (Connection connection = dataSource.getConnection()) {

            // O isolamento repetível garante que os três SELECTs enxerguem o mesmo
            // snapshot, mesmo com escritas concorrentes do worker.
            connection.setReadOnly(true);

            connection.setTransactionIsolation(
                    Connection.TRANSACTION_REPEATABLE_READ);

            connection.setAutoCommit(false);

            try {
                QueueSnapshot snapshot = readSnapshot(connection);

                connection.commit();

                return snapshot;

            } catch (SQLException | RuntimeException error) {

                // Desfaz a transação de leitura antes de devolver a conexão ao pool.
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }

                throw error;
            }
        }
    }

    /**
     * Executa as três consultas agregadas na conexão já configurada e monta a fotografia.
     *
     * @param connection conexão com transação somente leitura aberta
     * @return fotografia montada a partir dos três resultados
     * @throws SQLException se uma consulta falhar ou não devolver a linha agregada
     */
    private @NonNull QueueSnapshot readSnapshot(
            Connection connection) throws SQLException {

        long pendingPackets;
        long eligiblePackets;
        long retryWaitingPackets;
        double oldestPacketAge;

        // Só pacotes pendentes entram na fila; o filtro separa os elegíveis agora
        // dos que aguardam a próxima tentativa.
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

        // O estado é a chave do mapa; estados sem linhas simplesmente não aparecem.
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
