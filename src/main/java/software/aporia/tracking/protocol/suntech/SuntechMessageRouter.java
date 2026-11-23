package software.aporia.tracking.protocol.suntech;

import java.util.Objects;

/** Converte as capacidades de um quadro Suntech no plano de processamento do pacote. */
public final class SuntechMessageRouter {

    /** Determina todos os processamentos aplicáveis ao conteúdo do quadro. */
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
