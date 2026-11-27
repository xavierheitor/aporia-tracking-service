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

/**
 * Monta a coleta periódica das filas persistidas quando o perfil {@code monitor} está ativo.
 *
 * <p>{@code @EnableScheduling} habilita o agendamento usado por {@link QueueMetricsCollector}; o pool de uma
 * thread e o encerramento educado vêm de {@code spring.task.scheduling} em {@code application-monitor.yml}. O
 * repositório usa um pool reduzido e apenas lê agregados, sem participar das transações do gateway ou do
 * worker.</p>
 *
 * <p>Os beans abaixo só existem no perfil {@code monitor}; os demais perfis não criam o agendador nem
 * consultam a fila.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("monitor")
@EnableScheduling
public class MonitorConfiguration {

    /**
     * Cria o repositório de leitura das filas com o timeout aplicado a cada consulta.
     *
     * @param dataSource          pool do perfil monitor
     * @param queryTimeoutSeconds limite, em segundos, de cada consulta JDBC
     * @return repositório somente leitura, criado na inicialização do contexto
     * @throws IllegalArgumentException se o timeout não for positivo
     */
    @Bean
    public QueueMetricsRepository queueMetricsRepository(
            DataSource dataSource,
            @Value("${tracking.monitor.query-timeout-seconds}") int queryTimeoutSeconds) {

        return new QueueMetricsRepository(
                dataSource,
                queryTimeoutSeconds);
    }

    /**
     * Cria o publicador das métricas de fila.
     *
     * <p>Os gauges são registrados no contexto; até a primeira coleta eles retornam {@code NaN}, e {@code
     * tracking.queue.snapshot.available} permanece {@code 0}. A atualização posterior é feita por {@link
     * #queueMetricsCollector(QueueMetricsRepository, QueueMetrics)}.</p>
     *
     * @param registry registro Micrometer compartilhado pelo contexto
     * @return publicador de snapshots e contadores de coleta
     */
    @Bean
    @NullMarked
    public QueueMetrics queueMetrics(
            MeterRegistry registry) {

        return new QueueMetrics(registry);
    }

    /**
     * Cria o coletor agendado que consulta o repositório e publica o snapshot.
     *
     * @param repository repositório somente leitura das filas
     * @param metrics    publicador consultado pelos gauges
     * @return coletor cujo método {@code collect()} é agendado pelo contexto
     */
    @Bean
    public QueueMetricsCollector queueMetricsCollector(
            QueueMetricsRepository repository,
            QueueMetrics metrics) {

        return new QueueMetricsCollector(
                repository,
                metrics);
    }
}
