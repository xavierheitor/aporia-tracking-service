package software.aporia.tracking.protocol;

import java.util.Objects;

public record MessageProcessingPlan(
        boolean processTelemetry,
        boolean processCommandResponse,
        boolean processHeartbeat
        ) {

    public static MessageProcessingPlan from(
            ProtocolMessageCapabilities capabilities
    ) {
        Objects.requireNonNull(capabilities);

        return new MessageProcessingPlan(
                capabilities.hasTelemetry(),
                capabilities.hasCommandResponse(),
                capabilities.hasHeartbeat()
        );
    }

    public boolean hasWork() {
        return processTelemetry
                || processCommandResponse
                || processHeartbeat;
    }
}
