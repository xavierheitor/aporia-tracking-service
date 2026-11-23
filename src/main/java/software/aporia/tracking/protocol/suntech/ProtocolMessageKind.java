package software.aporia.tracking.protocol.suntech;

/** Classifica a finalidade principal de uma mensagem recebida do equipamento. */
public enum ProtocolMessageKind {
    /** Conteúdo usado para extrair leituras do equipamento. */
    TELEMETRY,
    /** Conteúdo de retorno a uma operação enviada ao equipamento. */
    COMMAND_RESPONSE,
    /** Sinal de presença do dispositivo. */
    HEARTBEAT,
    /** Cabeçalho para o qual não há categoria reconhecida. */
    UNKNOWN
}
