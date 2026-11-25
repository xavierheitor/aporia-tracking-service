package software.aporia.tracking.config;

import javax.sql.DataSource;

import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.micrometer.core.instrument.MeterRegistry;

import software.aporia.tracking.observability.metrics.QueueMetrics;
import software.aporia.tracking.observability.metrics.QueueMetricsCollector;
import software.aporia.tracking.storage.monitoring.QueueMetricsRepository;

@Configuration(proxyBeanMethods = false)
@Profile("monitor")
@EnableScheduling
public class MonitorConfiguration {

    @Bean
    public QueueMetricsRepository queueMetricsRepository(
            DataSource dataSource,
            @Value("${tracking.monitor.query-timeout-seconds}") int queryTimeoutSeconds) {

        return new QueueMetricsRepository(
                dataSource,
                queryTimeoutSeconds);
    }

    @Bean
    @NullMarked
    public QueueMetrics queueMetrics(
            MeterRegistry registry) {

        return new QueueMetrics(registry);
    }

    @Bean
    public QueueMetricsCollector queueMetricsCollector(
            QueueMetricsRepository repository,
            QueueMetrics metrics) {

        return new QueueMetricsCollector(
                repository,
                metrics);
    }
}
