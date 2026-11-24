package software.aporia.tracking.protocol.suntech;

import java.util.Objects;

/** Converte as capacidades de um quadro Suntech no plano de processamento do pacote. */
public final class SuntechMessageRouter {

    /**
     * Determina todos os processamentos aplicáveis ao conteúdo do quadro.
     *
     * @param payload bytes do conteúdo da mensagem
     * @return plano com os processamentos aplicáveis à mensagem
     */
    public MessageProcessingPlan route(byte[] payload) {

        Objects.requireNonNull(payload);

        ProtocolMessageCapabilities capabilities
                = SuntechMessageCapabilities.classify(payload);

        MessageProcessingPlan plan
                = MessageProcessingPlan.from(capabilities);

        // Não escolhe um único destino: o mesmo quadro pode exigir mais de um processamento.
        if (!plan.hasWork()) {
            throw new IllegalArgumentException(
                    "UNSUPPORTED_MESSAGE_CAPABILITIES"
            );
        }

        return plan;
    }
}
