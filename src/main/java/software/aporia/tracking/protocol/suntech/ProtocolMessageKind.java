package software.aporia.tracking.protocol.suntech;

/** Classifica a finalidade principal de uma mensagem recebida do equipamento. */
public enum ProtocolMessageKind {
    TELEMETRY,
    COMMAND_RESPONSE,
    HEARTBEAT,
    UNKNOWN
}
