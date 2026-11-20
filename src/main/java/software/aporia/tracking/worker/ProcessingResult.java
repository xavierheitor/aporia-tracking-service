package software.aporia.tracking.worker;

import java.util.UUID;

record ProcessingResult(
        UUID packetId,
        Status status,
        String errorCode,
        int attempts,
        long retryDelaySeconds
        ) {

    enum Status {
        EMPTY,
        PROCESSED,
        REJECTED,
        RETRY_SCHEDULED
    }

    static ProcessingResult empty() {
        return new ProcessingResult(
                null, Status.EMPTY, null, 0, 0
        );
    }

    static ProcessingResult processed(UUID packetId) {
        return new ProcessingResult(
                packetId, Status.PROCESSED, null, 0, 0
        );
    }

    static ProcessingResult rejected(
            UUID packetId,
            String errorCode,
            int attempts
    ) {
        return new ProcessingResult(
                packetId, Status.REJECTED, errorCode, attempts, 0
        );
    }

    static ProcessingResult retryScheduled(
            UUID packetId,
            String errorCode,
            int attempts,
            long delaySeconds
    ) {
        return new ProcessingResult(
                packetId,
                Status.RETRY_SCHEDULED,
                errorCode,
                attempts,
                delaySeconds
        );
    }
}
