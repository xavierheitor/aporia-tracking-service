package software.aporia.tracking.observability.metrics;

import java.sql.SQLException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import software.aporia.tracking.storage.monitoring.QueueMetricsRepository;

public final class QueueMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(
            QueueMetricsCollector.class);

    private final QueueMetricsRepository repository;
    private final QueueMetrics metrics;

    public QueueMetricsCollector(
            QueueMetricsRepository repository,
            QueueMetrics metrics) {

        this.repository = Objects.requireNonNull(repository);

        this.metrics = Objects.requireNonNull(metrics);
    }

    @Scheduled(fixedDelayString = "${tracking.monitor.collection-interval-ms}")
    public void collect() {

        long startedAt = System.nanoTime();

        try {
            metrics.publish(repository.collect());

        } catch (SQLException | RuntimeException error) {

            metrics.collectionFailed();

            log.error(
                    "Queue metrics collection failed",
                    error);

        } finally {

            metrics.recordCollectionDuration(
                    System.nanoTime() - startedAt);
        }
    }
}