package software.aporia.tracking.protocol.suntech;

/**
 * Classifica a finalidade principal de uma mensagem recebida do equipamento.
 *
 * <p>A classificação considera apenas o cabeçalho textual e não substitui as capacidades combinadas usadas
 * pelo roteamento. Em particular, {@code RES;STT;...} é {@link #COMMAND_RESPONSE} por cabeçalho, embora
 * também contenha telemetria.</p>
 */
public enum ProtocolMessageKind {
    /** Conteúdo usado para extrair leituras do equipamento, como {@code STT} e {@code ASTT}. */
    TELEMETRY,
    /**
     * Conteúdo de retorno a uma operação enviada ao equipamento, como {@code RES} e {@code RPR}; não confirma
     * a execução do comando.
     */
    COMMAND_RESPONSE,
    /** Sinal de presença do dispositivo, como {@code ALV}. */
    HEARTBEAT,
    /** Cabeçalho para o qual não há categoria reconhecida. */
    UNKNOWN
}
