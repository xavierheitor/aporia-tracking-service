package software.aporia.tracking.protocol.suntech;

/**
 * Enumera os tipos de processamento que podem coexistir em uma mesma mensagem Suntech.
 *
 * <p>As capacidades descrevem conteúdos reconhecidos no quadro, não a categoria exclusiva do cabeçalho. Uma
 * resposta {@code RES} com posição embutida carrega {@link #COMMAND_RESPONSE} e {@link #TELEMETRY} ao mesmo
 * tempo.</p>
 */
public enum MessageCapability {

    /** Conteúdo usado para extrair leituras do equipamento, com ou sem coordenadas. */
    TELEMETRY,
    /**
     * Conteúdo de retorno a uma operação enviada ao equipamento, como {@code RES} ou {@code RPR}.
     */
    COMMAND_RESPONSE,
    /** Sinal de presença do dispositivo, como {@code ALV}, que gera leitura sem posição. */
    HEARTBEAT
}
