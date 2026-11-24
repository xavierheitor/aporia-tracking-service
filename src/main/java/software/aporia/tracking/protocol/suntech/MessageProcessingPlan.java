package software.aporia.tracking.protocol.suntech;

import java.util.Objects;

/**
 * Indica quais partes da mensagem devem gerar telemetria, resposta de comando ou processamento de heartbeat.
 *
 * <p>As decisões são independentes: uma resposta com posição pode ativar
 * telemetria e resposta de comando simultaneamente.</p>
 *
 * @param processTelemetry indica que a mensagem contém telemetria
 * @param processCommandResponse indica que há uma resposta a registrar
 * @param processHeartbeat indica que há uma mensagem de presença
 */
public record MessageProcessingPlan(
        boolean processTelemetry,
        boolean processCommandResponse,
        boolean processHeartbeat
        ) {

    /**
     * Converte cada capacidade reconhecida em uma decisão independente de processamento.
     *
     * @param capabilities capacidades presentes na mensagem
     * @return plano com os processamentos aplicáveis à mensagem
     */
    public static MessageProcessingPlan from(
            ProtocolMessageCapabilities capabilities
    ) {
        Objects.requireNonNull(capabilities);

        return new MessageProcessingPlan(
                capabilities.hasTelemetry(),
                capabilities.hasCommandResponse(),
                capabilities.hasHeartbeat()
        );
    }

    /**
     * Indica se há pelo menos um processamento aplicável.
     *
     * @return verdadeiro se ao menos um caminho de processamento está habilitado
     */
    public boolean hasWork() {
        return processTelemetry
                || processCommandResponse
                || processHeartbeat;
    }
}
