package software.aporia.tracking.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

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
    public TrackingWorker trackingWorker(DataSource dataSource) {
        // Criar o worker não consulta a fila; o componente de ciclo de vida inicia as chamadas.
        return new TrackingWorker(dataSource);
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
}
