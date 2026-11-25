package software.aporia.tracking.observability.metrics;

import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.NullMarked;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Instrumenta os desfechos confirmados do processamento e as falhas operacionais do worker.
 */
@Component
@Profile("worker")
@NullMarked
public final class WorkerMetrics {

    private final Counter processed;
    private final Counter rejected;
    private final Counter retryScheduled;
    private final Counter failedCycles;
    private final Counter emptyCycles;

    private final Timer processingDuration;

    public WorkerMetrics(MeterRegistry registry) {

        processed = Counter.builder(
                "tracking.packets.processed")
                .description("Successfully processed packets")
                .register(registry);

        rejected = Counter.builder(
                "tracking.packets.rejected")
                .description("Permanently rejected packets")
                .register(registry);

        retryScheduled = Counter.builder(
                "tracking.packets.retry.scheduled")
                .description("Persisted packet retry schedules")
                .register(registry);

        failedCycles = Counter.builder(
                "tracking.worker.cycles.failed")
                .description("Worker cycles that failed")
                .register(registry);

        emptyCycles = Counter.builder(
                "tracking.worker.cycles.empty")
                .description("Worker cycles without eligible packets")
                .register(registry);

        processingDuration = Timer.builder(
                "tracking.packet.processing")
                .description("Committed packet processing duration")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void packetProcessed() {
        processed.increment();
    }

    public void packetRejected() {
        rejected.increment();
    }

    public void retryScheduled() {
        retryScheduled.increment();
    }

    public void cycleFailed() {
        failedCycles.increment();
    }

    public void cycleEmpty() {
        emptyCycles.increment();
    }

    public void recordProcessingDuration(long elapsedNanos) {
        processingDuration.record(
                elapsedNanos,
                TimeUnit.NANOSECONDS);
    }
}
