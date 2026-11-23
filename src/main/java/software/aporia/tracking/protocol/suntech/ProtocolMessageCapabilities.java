package software.aporia.tracking.protocol.suntech;

import java.util.Set;

/**
 * Agrupa as capacidades reconhecidas em uma mensagem para orientar o roteamento.
 *
 * @param values capacidades reconhecidas no quadro
 */
public record ProtocolMessageCapabilities(
        @SuppressWarnings("MismatchedQueryAndUpdateOfCollection") // Cópia imutável fornecida pelo chamador.
        Set<MessageCapability> values
        ) {

    /**
     * Congela o conjunto de capacidades para preservar a classificação durante o processamento.
     *
     * @param values capacidades presentes na mensagem
     */
    public ProtocolMessageCapabilities {

        values = Set.copyOf(values);
    }

    /**
     * Consulta a presença de uma capacidade específica no conjunto reconhecido.
     *
     * @param capability capacidade que se deseja verificar
     * @return verdadeiro se a classificação contém a capacidade
     */
    public boolean supports(MessageCapability capability) {
        return values.contains(capability);
    }

    /**
     * Indica que a mensagem contém uma leitura de telemetria.
     *
     * @return verdadeiro se há telemetria a processar
     */
    public boolean hasTelemetry() {
        return supports(MessageCapability.TELEMETRY);
    }

    /**
     * Indica que há uma resposta de comando a registrar.
     *
     * @return verdadeiro se há resposta de comando
     */
    public boolean hasCommandResponse() {
        return supports(MessageCapability.COMMAND_RESPONSE);
    }

    /**
     * Indica que a mensagem comunica a presença do equipamento.
     *
     * @return verdadeiro se há indicação de presença
     */
    public boolean hasHeartbeat() {
        return supports(MessageCapability.HEARTBEAT);
    }
}
