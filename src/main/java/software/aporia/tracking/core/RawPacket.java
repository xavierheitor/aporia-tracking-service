package software.aporia.tracking.core;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Representa uma recepção persistida com seu conteúdo original e o instante de
 * recepção.
 *
 * @param id identificador do registro
 * @param protocol identificador do protocolo no cadastro
 * @param deviceIdentity identidade do equipamento no protocolo
 * @param payload bytes originais do quadro ou comando
 * @param receivedAt instante em que o gateway recebeu o pacote
 */
public record RawPacket(
        UUID id,
        String protocol,
        String deviceIdentity,
        byte[] payload,
        Instant receivedAt
        ) {

    /**
     * Construtor do RawPacket. Obrigatório fornecer um identificador de
     * registro, um instante de recepção, um identificador de protocolo e uma
     * identidade de equipamento. O payload deve ser um array de bytes válido e
     * não nulo.
     *
     * @param id identificador do registro
     * @param protocol identificador do protocolo no cadastro
     * @param deviceIdentity identidade do equipamento no protocolo
     * @param payload bytes originais do quadro ou comando
     * @param receivedAt instante em que o gateway recebeu o pacote
     * @throws NullPointerException se id, receivedAt ou payload for nulo
     * @throws IllegalArgumentException se protocolo ou identidade estiverem ausentes, ou se o payload tiver tamanho inválido
     */
    public RawPacket {
        Objects.requireNonNull(id, "Packet ID is required");
        Objects.requireNonNull(receivedAt, "Reception time is required");

        if (protocol == null || protocol.isBlank()) {
            throw new IllegalArgumentException("Protocol is required");
        }

        if (deviceIdentity == null || deviceIdentity.isBlank()) {
            throw new IllegalArgumentException("Device identity is required");
        }

        Objects.requireNonNull(payload, "Payload is required");

        if (payload.length == 0 || payload.length > 65536) {
            throw new IllegalArgumentException("Invalid payload size");
        }

        payload = payload.clone();
    }

    /**
     * Retorna uma cópia do payload original.
     *
     * @return uma cópia do payload original
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /**
     * Verifica se este RawPacket é igual a outro objeto.
     *
     * @param obj objeto a ser comparado
     * @return true se os RawPacket forem iguais, false caso contrário
     */
    @Override
    public boolean equals(Object obj) {
        return obj instanceof RawPacket other
                && Objects.equals(id, other.id)
                && Objects.equals(protocol, other.protocol)
                && Objects.equals(deviceIdentity, other.deviceIdentity)
                && Arrays.equals(payload, other.payload)
                && Objects.equals(receivedAt, other.receivedAt);
    }

    /**
     * Retorna o hash code deste RawPacket.
     *
     * @return o hash code deste RawPacket
     */
    @Override
    public int hashCode() {
        return Objects.hash(
                id, protocol, deviceIdentity,
                Arrays.hashCode(payload), receivedAt
        );
    }
}
