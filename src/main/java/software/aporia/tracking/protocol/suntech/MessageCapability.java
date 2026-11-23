package software.aporia.tracking.protocol.suntech;

/** Enumera os tipos de processamento que podem coexistir em uma mesma mensagem. */
public enum MessageCapability {

    /** Conteúdo usado para extrair leituras do equipamento. */
    TELEMETRY,
    /** Conteúdo de retorno a uma operação enviada ao equipamento. */
    COMMAND_RESPONSE,
    /** Sinal de presença do dispositivo. */
    HEARTBEAT
}
