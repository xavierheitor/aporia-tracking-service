package software.aporia.tracking.storage.device;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.NonNull;

/**
 * Identifica o equipamento cadastrado, seu tenant e o par protocolo e identidade usado na conexão.
 *
 * @param tenantId tenant ao qual pertence o equipamento
 * @param deviceId identificador do equipamento no cadastro
 * @param protocol identificador do protocolo no cadastro
 * @param identity identidade declarada pelo equipamento no protocolo
 */
public record RegisteredDevice(
        UUID tenantId,
        UUID deviceId,
        @NonNull String protocol,
        String identity
        ) {

    /**
     * Exige os identificadores usados para vincular a conexão ao cadastro e ao tenant.
     *
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @param protocol identificador do protocolo do equipamento
     * @param identity identidade declarada pelo rastreador no protocolo
     */
    public RegisteredDevice {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(protocol);
        Objects.requireNonNull(identity);
    }
}
