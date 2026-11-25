package software.aporia.tracking.observability.metrics;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import software.aporia.tracking.storage.monitoring.QueueSnapshot;

public final class QueueMetrics {

    private record CollectedSnapshot(
            QueueSnapshot snapshot,
            long collectedAtNanos) {
    }

    private final AtomicReference<CollectedSnapshot> latest = new AtomicReference<>();

    private final Counter collectionErrors;
    private final Counter collectionCompleted;
    private final Timer collectionDuration;

    public QueueMetrics(MeterRegistry registry) {

        collectionErrors = Counter.builder(
                "tracking.queue.collection.errors")
                .register(registry);

        collectionCompleted = Counter.builder(
                "tracking.queue.collection.completed")
                .register(registry);

        collectionDuration = Timer.builder(
                "tracking.queue.collection.duration")
                .publishPercentileHistogram()
                .register(registry);

        registerGauge(
                registry,
                "tracking.queue.packets.pending",
                QueueSnapshot::pendingPackets,
                null);

        registerGauge(
                registry,
                "tracking.queue.packets.eligible",
                QueueSnapshot::eligiblePackets,
                null);

        registerGauge(
                registry,
                "tracking.queue.packets.retry.waiting",
                QueueSnapshot::retryWaitingPackets,
                null);

        registerGauge(
                registry,
                "tracking.queue.packets.oldest.age",
                QueueSnapshot::oldestPacketAgeSeconds,
                "seconds");

        registerGauge(
                registry,
                "tracking.queue.outbox.pending",
                QueueSnapshot::pendingOutboxEvents,
                null);

        registerGauge(
                registry,
                "tracking.queue.outbox.oldest.age",
                QueueSnapshot::oldestOutboxAgeSeconds,
                "seconds");

        for (String status : new String[] {
                "queued",
                "sending",
                "transmitting",
                "sent",
                "responded",
                "timeout",
                "delivery_failed",
                "delivery_unknown",
                "cancelled"
        }) {
            Gauge.builder(
                    "tracking.queue.commands",
                    this,
                    metrics -> metrics.readSnapshot(
                            snapshot -> snapshot
                                    .commandsByStatus()
                                    .getOrDefault(status, 0L)))
                    .tag("status", status)
                    .register(registry);
        }

        Gauge.builder(
                "tracking.queue.snapshot.age",
                this,
                QueueMetrics::snapshotAgeSeconds)
                .baseUnit("seconds")
                .register(registry);

        Gauge.builder(
                "tracking.queue.snapshot.available",
                this,
                metrics -> metrics.latest.get() == null
                        ? 0.0
                        : 1.0)
                .register(registry);
    }

    private void registerGauge(
            MeterRegistry registry,
            String name,
            ToDoubleFunction<QueueSnapshot> reader,
            String baseUnit) {

        var builder = Gauge.builder(
                name,
                this,
                metrics -> metrics.readSnapshot(reader));

        if (baseUnit != null) {
            builder.baseUnit(baseUnit);
        }

        builder.register(registry);
    }

    private double readSnapshot(
            ToDoubleFunction<QueueSnapshot> reader) {

        CollectedSnapshot current = latest.get();

        if (current == null) {
            return Double.NaN;
        }

        return reader.applyAsDouble(current.snapshot());
    }

    public double snapshotAgeSeconds() {

        CollectedSnapshot current = latest.get();

        if (current == null) {
            return Double.NaN;
        }

        return (System.nanoTime()
                - current.collectedAtNanos())
                / 1_000_000_000.0;
    }

    public void publish(QueueSnapshot snapshot) {

        latest.set(
                new CollectedSnapshot(
                        Objects.requireNonNull(snapshot),
                        System.nanoTime()));

        collectionCompleted.increment();
    }

    public void collectionFailed() {
        collectionErrors.increment();
    }

    public void recordCollectionDuration(
            long elapsedNanos) {

        collectionDuration.record(
                elapsedNanos,
                TimeUnit.NANOSECONDS);
    }
}