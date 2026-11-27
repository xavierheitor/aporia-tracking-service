package software.aporia.tracking.protocol.suntech;

import java.util.Objects;

/**
 * Relaciona o cabeçalho de uma mensagem Suntech à sua categoria principal.
 *
 * <p>Para conteúdos combinados, como {@code RES} com posição, as capacidades preservam as duas finalidades;
 * esta classificação isolada representa apenas o cabeçalho. O roteamento do worker usa {@link
 * SuntechMessageCapabilities}, não esta classe. A categoria também não confirma a execução de um comando.</p>
 */
public final class SuntechMessageClassifier {

    /**
     * Classifica a finalidade indicada pelo tipo textual da mensagem.
     *
     * @param messageType tipo declarado no cabeçalho da mensagem
     * @return categoria principal indicada pelo cabeçalho
     * @throws NullPointerException se {@code messageType} for nulo
     */
    public ProtocolMessageKind classify(String messageType) {

        Objects.requireNonNull(messageType, "messageType");

        // A comparação é exata e diferencia maiúsculas; qualquer outro texto resulta em UNKNOWN.
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
