package software.aporia.tracking.observability.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import software.aporia.tracking.worker.WorkerLifecycle;

@Component("trackingWorkerHealthIndicator")
@Profile("worker")
public final class WorkerHealthIndicator
        implements HealthIndicator {

    private final WorkerLifecycle lifecycle;

    public WorkerHealthIndicator(WorkerLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public Health health() {

        if (!lifecycle.isRunning()) {
            return Health.down()
                    .withDetail("reason", "WORKER_NOT_RUNNING")
                    .build();
        }

        return Health.up().build();
    }
}