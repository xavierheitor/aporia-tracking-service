package software.aporia.tracking.config;

import java.util.List;
import javax.sql.DataSource;

import org.jspecify.annotations.NullMarked;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;

import software.aporia.tracking.observability.metrics.WorkerMetrics;
import software.aporia.tracking.protocol.DecoderRegistry;
import software.aporia.tracking.protocol.suntech.SuntechDecoder;
import software.aporia.tracking.protocol.teltonika.TeltonikaAvl;
import software.aporia.tracking.worker.TrackingWorker;
import software.aporia.tracking.worker.WorkerLifecycle;

/**
 * Monta o consumidor de pacotes quando o perfil {@code worker} está ativo. {@link TrackingWorker} orquestra cada
 * pacote; {@link WorkerLifecycle} mantém as consultas à fila em uma thread e coordena o encerramento.
 *
 * <p>O worker criado aqui é global: o filtro de tenant é nulo e a reserva pode escolher pacotes de qualquer
 * tenant. O contexto mantém uma instância do worker e uma thread de processamento; a concorrência entre
 * instâncias, quando existir, é resolvida pela reserva no PostgreSQL com {@code SKIP LOCKED}.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
public class WorkerConfiguration {
    /**
     * Cria o consumidor global com os decodificadores padrão e a persistência transacional.
     *
     * @param dataSource      pool gerenciado pelo Spring e repassado à camada de persistência
     * @param decoderRegistry decodificadores indexados pela versão persistida no pacote
     * @param metrics         métricas dos desfechos confirmados do worker
     * @return worker pronto para processar pacotes, ainda sem laço de execução ativo
     * @throws NullPointerException se o registro de decodificadores ou as métricas forem nulos
     */
    @Bean
    public TrackingWorker trackingWorker(
            DataSource dataSource,
            DecoderRegistry decoderRegistry,
            WorkerMetrics metrics) {

        // O filtro nulo cria o consumidor global; cada chamada usa a própria transação no repositório.
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

    /**
     * Cria o registro compartilhado dos decodificadores disponíveis.
     *
     * <p>O mapa é imutável após a construção. A versão persistida no pacote escolhe o decodificador,
     * inclusive para pacotes recebidos antes de uma atualização; versões não registradas impedem a reserva
     * pelo worker.</p>
     *
     * @return registro com os decodificadores Suntech e Teltonika
     * @throws IllegalArgumentException se houver versões duplicadas ou em branco
     */
    @Bean
    public DecoderRegistry decoderRegistry() {

        return new DecoderRegistry(
                List.of(
                        new SuntechDecoder(),
                        new TeltonikaAvl()));
    }

    /**
     * Registra o gauge de idade do heartbeat do worker.
     *
     * <p>O gauge é criado na inicialização e consulta {@link WorkerLifecycle#getHeartbeatAgeSeconds()} a cada
     * coleta. Não há tags, então a cardinalidade é fixa; o valor cresce continuamente se o worker parar, o
     * que é explorado pelo indicador de saúde.</p>
     *
     * @param lifecycle ciclo de vida consultado pelo gauge
     * @return componente que registra o gauge em segundos
     */
    @Bean
    @NullMarked
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
