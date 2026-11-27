package software.aporia.tracking.observability.metrics;

import java.sql.SQLException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import software.aporia.tracking.storage.monitoring.QueueMetricsRepository;

/**
 * Executa a coleta periódica das métricas de fila no perfil {@code monitor}.
 *
 * <p>O bean é criado por {@code MonitorConfiguration} e {@link #collect()} é agendado por {@code @Scheduled}
 * com {@code fixedDelay} de {@code tracking.monitor.collection-interval-ms}. O agendador tem uma thread em
 * {@code application-monitor.yml}; um ciclo lento atrasa o próximo, mas não sobrepõe execuções. Falhas são
 * registradas e não interrompem os ciclos seguintes.</p>
 *
 * <p>A coleta é somente leitura e o snapshot só é publicado depois que o repositório confirma o commit da
 * consulta. Não há transação abrangendo a publicação nem o registro das métricas.</p>
 */
public final class QueueMetricsCollector {

    /** Logger usado para registrar falhas de coleta sem expor dados do banco. */
    private static final Logger log = LoggerFactory.getLogger(
            QueueMetricsCollector.class);

    /** Repositório somente leitura que produz o snapshot. */
    private final QueueMetricsRepository repository;
    /** Publicador consultado pelos gauges e contadores de coleta. */
    private final QueueMetrics metrics;

    /**
     * Guarda as dependências do coletor.
     *
     * @param repository repositório somente leitura das filas
     * @param metrics    publicador dos snapshots e contadores
     * @throws NullPointerException se uma das dependências for nula
     */
    public QueueMetricsCollector(
            QueueMetricsRepository repository,
            QueueMetrics metrics) {

        this.repository = Objects.requireNonNull(repository);

        this.metrics = Objects.requireNonNull(metrics);
    }

    /**
     * Coleta e publica um snapshot; falhas de SQL ou de runtime são contabilizadas e registradas sem propagar
     * para o agendador.
     */
    @Scheduled(fixedDelayString = "${tracking.monitor.collection-interval-ms}")
    public void collect() {

        long startedAt = System.nanoTime();

        try {
            // A publicação só ocorre depois que o repositório confirma o commit da leitura.
            metrics.publish(repository.collect());

        } catch (SQLException | RuntimeException error) {

            metrics.collectionFailed();

            log.error(
                    "Queue metrics collection failed",
                    error);

        } finally {

            // O tempo é registrado no finally para incluir também os ciclos que falharam.
            metrics.recordCollectionDuration(
                    System.nanoTime() - startedAt);
        }
    }
}