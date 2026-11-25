package software.aporia.tracking.observability.metrics;

import org.jspecify.annotations.NullMarked;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.aporia.tracking.command.dispatcher.CommandDispatcher.DispatchResult;
import software.aporia.tracking.storage.monitoring.QueueSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NullMarked
class MetricsContractTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void keepsQueueUnavailableUntilFirstSnapshotAndPreservesItOnCollectionFailure() {
        var metrics = new QueueMetrics(registry);
        assertEquals(0.0, registry.get("tracking.queue.snapshot.available").gauge().value());
        assertTrue(Double.isNaN(registry.get("tracking.queue.packets.pending").gauge().value()));
        assertTrue(Double.isNaN(metrics.snapshotAgeSeconds()));

        metrics.publish(new QueueSnapshot(7, 3, 4, 20, Map.of("queued", 2L), 1, 10));
        metrics.collectionFailed();

        assertEquals(1.0, registry.get("tracking.queue.snapshot.available").gauge().value());
        assertEquals(7.0, registry.get("tracking.queue.packets.pending").gauge().value());
        assertEquals(2.0, registry.get("tracking.queue.commands").tag("status", "queued").gauge().value());
        assertEquals(0.0, registry.get("tracking.queue.commands").tag("status", "sent").gauge().value());
        assertEquals(1.0, registry.get("tracking.queue.collection.errors").counter().count());
    }

    @Test
    void recordsEveryDispatchResultWithItsOwnCounterAndTimer() {
        var metrics = new CommandMetrics(registry);
        for (var result : DispatchResult.values()) {
            metrics.dispatchCompleted(result, 100);
        }
        for (var counter : registry.find("tracking.command.dispatch").counters()) {
            assertEquals(1.0, counter.count());
        }
        for (var timer : registry.find("tracking.command.dispatch.duration").timers()) {
            assertEquals(1L, timer.count());
        }
    }

    @Test
    void groupsUnknownProtocolsInOther() {
        var metrics = new GatewayMetrics(registry);
        metrics.packetIngested("future-protocol");
        assertEquals(1.0, registry.get("tracking.packets.ingested").tag("protocol", "other").counter().count());
        assertEquals(0.0, registry.get("tracking.packets.ingested").tag("protocol", "suntech").counter().count());
    }
}
