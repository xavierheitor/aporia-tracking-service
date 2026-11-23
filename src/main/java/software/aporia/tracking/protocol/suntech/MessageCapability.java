package software.aporia.tracking.protocol.suntech;

/** Enumera os tipos de processamento que podem coexistir em uma mesma mensagem. */
public enum MessageCapability {

    TELEMETRY,
    COMMAND_RESPONSE,
    HEARTBEAT
}
