package software.aporia.tracking.observability.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import software.aporia.tracking.worker.WorkerLifecycle;

/**
 * Traduz o estado do worker para o endpoint de saúde no perfil {@code worker}.
 *
 * <p>O indicador é registrado como {@code trackingWorkerHealthIndicator} e participa dos grupos {@code
 * liveness} e {@code readiness} definidos em {@code application-worker.yml}. Ele verifica se a thread de
 * processamento está viva e se o tempo desde o início do último ciclo não excede {@code
 * tracking.worker.heartbeat-timeout-seconds}; em caso de falha, responde {@code DOWN} com o motivo.</p>
 *
 * <p>O heartbeat é atualizado no início de cada ciclo, não ao final, e continua envelhecendo após uma parada.
 * A consulta é somente leitura e pode ser chamada concorrentemente.</p>
 */
@Component("trackingWorkerHealthIndicator")
@Profile("worker")
public final class WorkerHealthIndicator
        implements HealthIndicator {

    /** Ciclo de vida do worker consultado a cada verificação. */
    private final WorkerLifecycle lifecycle;

    /** Limite positivo, em segundos, para considerar o heartbeat expirado. */
    private final long heartbeatTimeoutSeconds;

    /**
     * Guarda o ciclo de vida e valida o limite de heartbeat.
     *
     * @param lifecycle               ciclo de vida consultado
     * @param heartbeatTimeoutSeconds limite, em segundos, para considerar o heartbeat expirado
     * @throws IllegalArgumentException se o limite não for positivo
     */
    public WorkerHealthIndicator(
            WorkerLifecycle lifecycle,
            @Value("${tracking.worker.heartbeat-timeout-seconds}") long heartbeatTimeoutSeconds) {

        if (heartbeatTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "Worker heartbeat timeout must be positive");
        }

        this.lifecycle = lifecycle;
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    /**
     * Avalia a execução e o heartbeat do worker.
     *
     * @return {@code UP} com a idade do heartbeat ou {@code DOWN} com o motivo da indisponibilidade
     */
    @Override
    public Health health() {

        if (!lifecycle.isRunning()) {
            return Health.down()
                    .withDetail(
                            "reason",
                            "WORKER_NOT_RUNNING")
                    .build();
        }

        double heartbeatAge = lifecycle.getHeartbeatAgeSeconds();

        // Thread viva não basta: o último ciclo pode ter começado há muito tempo.
        if (heartbeatAge > heartbeatTimeoutSeconds) {
            return Health.down()
                    .withDetail(
                            "reason",
                            "WORKER_HEARTBEAT_EXPIRED")
                    .withDetail(
                            "heartbeatAgeSeconds",
                            heartbeatAge)
                    .build();
        }

        return Health.up()
                .withDetail(
                        "heartbeatAgeSeconds",
                        heartbeatAge)
                .build();
    }
}