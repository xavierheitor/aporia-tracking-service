package software.aporia.tracking.observability.health;

import org.springframework.beans.factory.annotation.Value;
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
    private final long heartbeatTimeoutSeconds;

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