package software.aporia.tracking.protocol;

import java.util.Set;

public record ProtocolMessageCapabilities(
        @SuppressWarnings("MismatchedQueryAndUpdateOfCollection") // Cópia imutável fornecida pelo chamador.
        Set<MessageCapability> values
        ) {

    public ProtocolMessageCapabilities {

        values = Set.copyOf(values);
    }

    public boolean supports(MessageCapability capability) {
        return values.contains(capability);
    }

    public boolean hasTelemetry() {
        return supports(MessageCapability.TELEMETRY);
    }

    public boolean hasCommandResponse() {
        return supports(MessageCapability.COMMAND_RESPONSE);
    }

    public boolean hasHeartbeat() {
        return supports(MessageCapability.HEARTBEAT);
    }
}
