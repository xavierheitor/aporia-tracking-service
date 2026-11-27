package software.aporia.tracking.protocol.suntech;

import java.util.Objects;

/**
 * Converte as capacidades de um quadro Suntech no plano de processamento do pacote.
 *
 * <p>O roteador não valida identidade nem campos: ele apenas classifica o cabeçalho e deriva as flags de
 * processamento. Um quadro sem capacidade reconhecida é rejeitado, pois não geraria telemetria, resposta nem
 * heartbeat. A classe não guarda estado e pode ser descartada após a chamada.</p>
 */
public final class SuntechMessageRouter {

    /**
     * Determina todos os processamentos aplicáveis ao conteúdo do quadro.
     *
     * @param payload bytes do conteúdo da mensagem
     * @return plano com os processamentos aplicáveis à mensagem
     * @throws NullPointerException se {@code payload} for nulo
     * @throws IllegalArgumentException se o cabeçalho não ativar nenhum processamento
     */
    public MessageProcessingPlan route(byte[] payload) {

        Objects.requireNonNull(payload);

        ProtocolMessageCapabilities capabilities
                = SuntechMessageCapabilities.classify(payload);

        MessageProcessingPlan plan
                = MessageProcessingPlan.from(capabilities);

        // Não escolhe um único destino: o mesmo quadro pode exigir mais de um processamento.
        // classificar não lança para cabeçalho desconhecido; o plano vazio vira erro aqui.
        if (!plan.hasWork()) {
            throw new IllegalArgumentException(
                    "UNSUPPORTED_MESSAGE_CAPABILITIES"
            );
        }

        return plan;
    }
}
