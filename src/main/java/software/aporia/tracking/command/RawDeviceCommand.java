package software.aporia.tracking.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Agrupa os bytes de um comando com seu protocolo e os identificadores de comando, tenant e dispositivo.
 *
 * @param commandId identificador persistido do comando
 * @param tenantId tenant ao qual pertence o equipamento
 * @param deviceId identificador do equipamento no cadastro
 * @param protocol identificador do protocolo no cadastro
 * @param payload bytes originais do quadro ou comando
 */
public record RawDeviceCommand(
        UUID commandId,
        UUID tenantId,
        UUID deviceId,
        String protocol,
        byte[] payload
        ) {

    /** Limite de bytes aceitos no conteúdo de um comando. */
    private static final int MAX_COMMAND_SIZE = 8192;

    /**
     * Valida o destinatário e o tamanho do comando e conserva uma cópia de seus bytes.
     *
     * @param commandId identificador do comando na fila
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @param protocol identificador do protocolo do equipamento
     * @param payload bytes do conteúdo da mensagem
     * @throws NullPointerException se algum componente for nulo
     * @throws IllegalArgumentException se o protocolo estiver em branco ou o tamanho do payload for inválido
     */
    public RawDeviceCommand {

        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(payload, "payload");

        if (protocol.isBlank()) {
            throw new IllegalArgumentException(
                    "INVALID_PROTOCOL"
            );
        }

        if (payload.length == 0
                || payload.length > MAX_COMMAND_SIZE) {
            throw new IllegalArgumentException(
                    "INVALID_COMMAND_SIZE"
            );
        }

        payload = payload.clone();
    }

    /**
     * Entrega os bytes do comando em um array independente.
     *
     * @return cópia dos bytes protegidos pela instância
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
