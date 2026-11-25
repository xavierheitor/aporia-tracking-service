package software.aporia.tracking.config;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.Gauge;
import software.aporia.tracking.observability.metrics.WorkerMetrics;
import software.aporia.tracking.protocol.DecoderRegistry;
import software.aporia.tracking.protocol.suntech.SuntechDecoder;
import software.aporia.tracking.protocol.teltonika.TeltonikaAvl;
import software.aporia.tracking.worker.TrackingWorker;
import software.aporia.tracking.worker.WorkerLifecycle;

/**
 * Monta o consumidor de pacotes quando o perfil {@code worker} está ativo. {@link TrackingWorker} orquestra cada
 * pacote; {@link WorkerLifecycle} mantém as consultas à fila em uma thread e coordena o encerramento.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
public class WorkerConfiguration {
    /**
     * Cria o consumidor global com os decodificadores padrão e a persistência transacional.
     *
     * @param dataSource pool gerenciado pelo Spring e repassado à camada de persistência
     * @return worker pronto para processar pacotes, ainda sem laço de execução ativo
     */
    @Bean
    public TrackingWorker trackingWorker(
            DataSource dataSource,
            DecoderRegistry decoderRegistry,
            WorkerMetrics metrics) {

        return new TrackingWorker(
                dataSource,
                null,
                decoderRegistry,
                metrics);
    }

    /**
     * Conecta o laço de processamento à partida e à parada do contexto.
     *
     * @param trackingWorker consumidor usado em cada ciclo
     * @return componente responsável pela thread de processamento
     */
    @Bean
    public WorkerLifecycle workerLifecycle(
            TrackingWorker trackingWorker) {
        return new WorkerLifecycle(trackingWorker);
    }

    @Bean
    public DecoderRegistry decoderRegistry() {

        return new DecoderRegistry(
                List.of(
                        new SuntechDecoder(),
                        new TeltonikaAvl()));
    }

    @Bean
    public MeterBinder workerGauges(
            WorkerLifecycle lifecycle) {

        return registry -> Gauge.builder(
                "tracking.worker.heartbeat.age",
                lifecycle,
                WorkerLifecycle::getHeartbeatAgeSeconds)
                .description("Time since the last worker cycle started")
                .baseUnit("seconds")
                .register(registry);
    }
}
