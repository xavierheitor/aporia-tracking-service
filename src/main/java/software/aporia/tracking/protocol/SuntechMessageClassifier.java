package software.aporia.tracking.protocol;

import java.util.Objects;

public final class SuntechMessageClassifier {

    public ProtocolMessageKind classify(String messageType) {

        Objects.requireNonNull(messageType, "messageType");

        return switch (messageType) {
            case "STT", "ASTT" ->
                ProtocolMessageKind.TELEMETRY;

            case "RES", "RPR" ->
                ProtocolMessageKind.COMMAND_RESPONSE;

            case "ALV" ->
                ProtocolMessageKind.HEARTBEAT;

            default ->
                ProtocolMessageKind.UNKNOWN;
        };
    }
}
