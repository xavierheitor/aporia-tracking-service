package software.aporia.tracking.protocol;

import java.util.Objects;

public final class SuntechMessageRouter {

    public MessageProcessingPlan route(byte[] payload) {

        Objects.requireNonNull(payload);

        ProtocolMessageCapabilities capabilities
                = SuntechMessageCapabilities.classify(payload);

        MessageProcessingPlan plan
                = MessageProcessingPlan.from(capabilities);

        if (!plan.hasWork()) {
            throw new IllegalArgumentException(
                    "UNSUPPORTED_MESSAGE_CAPABILITIES"
            );
        }

        return plan;
    }
}
