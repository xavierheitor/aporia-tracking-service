package software.aporia.tracking.protocol.suntech;

import java.util.Objects;

/**
 * Relaciona o cabeçalho de uma mensagem Suntech à sua categoria principal.
 * Para conteúdos combinados, como RES com posição, as capacidades preservam
 * as duas finalidades; esta classificação isolada representa apenas o cabeçalho.
 */
public final class SuntechMessageClassifier {

    /**
     * Classifica a finalidade indicada pelo tipo textual da mensagem.
     *
     * @param messageType tipo declarado no cabeçalho da mensagem
     * @return categoria principal indicada pelo cabeçalho
     */
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
