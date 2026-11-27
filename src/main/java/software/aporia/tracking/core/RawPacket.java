package software.aporia.tracking.core;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Representa os bytes originais de uma recepção com os metadados que a identificam.
 *
 * <p>É um valor imutável: o construtor copia o payload e o acessor devolve
 * outra cópia, então alterações no array do chamador não mudam o conteúdo
 * guardado. A validação aqui é apenas de forma; autenticar o equipamento e
 * decidir a persistência pertencem à ingestão. Depois de construído, pode ser
 * compartilhado entre threads.</p>
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
     * identidade de equipamento. O payload deve ter entre 1 e 65536 bytes.
     *
     * @param id identificador do registro
     * @param protocol identificador do protocolo no cadastro
     * @param deviceIdentity identidade do equipamento no protocolo
     * @param payload bytes originais do quadro ou comando
     * @param receivedAt instante em que o gateway recebeu o pacote
     * @throws NullPointerException se id, payload ou receivedAt for nulo
     * @throws IllegalArgumentException se protocolo ou identidade estiverem
     * ausentes, ou se o payload estiver vazio ou maior que 65536 bytes
     */
    public RawPacket {
        // Valida identificador e instante; em seguida os textos, o payload e o tamanho.
        Objects.requireNonNull(id, "Packet ID is required");
        Objects.requireNonNull(receivedAt, "Reception time is required");

        if (protocol == null || protocol.isBlank()) {
            throw new IllegalArgumentException("Protocol is required");
        }

        if (deviceIdentity == null || deviceIdentity.isBlank()) {
            throw new IllegalArgumentException("Device identity is required");
        }

        Objects.requireNonNull(payload, "Payload is required");

        // O teto de 64 KiB limita a memória ocupada por um único pacote.
        if (payload.length == 0 || payload.length > 65536) {
            throw new IllegalArgumentException("Invalid payload size");
        }

        // Copia o array porque o chamador ainda pode reutilizar ou alterar o buffer de leitura.
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
        // Arrays não sobrescrevem equals; o payload precisa ser comparado byte a byte.
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
        // Usa o mesmo critério do equals, inclusive o hash do conteúdo do array.
        return Objects.hash(
                id, protocol, deviceIdentity,
                Arrays.hashCode(payload), receivedAt
        );
    }
}
