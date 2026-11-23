package software.aporia.tracking.protocol.suntech;

import java.util.Objects;

/** Relaciona o cabeçalho de uma mensagem Suntech à sua categoria principal. */
public final class SuntechMessageClassifier {

    /** Classifica a finalidade indicada pelo tipo textual da mensagem. */
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
