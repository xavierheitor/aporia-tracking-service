package software.aporia.tracking.storage.monitoring;

import java.util.Map;
import java.util.Objects;

/**
 * Fotografia imutável das filas persistidas.
 */
public record QueueSnapshot(
        long pendingPackets,
        long eligiblePackets,
        long retryWaitingPackets,
        double oldestPacketAgeSeconds,
        Map<String, Long> commandsByStatus,
        long pendingOutboxEvents,
        double oldestOutboxAgeSeconds) {

    public QueueSnapshot {
        commandsByStatus = Map.copyOf(
                Objects.requireNonNull(commandsByStatus));
    }
}