
package software.aporia.tracking.protocol;

import java.util.Arrays;
import java.util.Objects;

/**
 * Representa um quadro completo emitido pela sessão de protocolo, com a identidade e a indicação de confirmação ao rastreador.
 *
 * @param deviceIdentity identidade do equipamento no protocolo
 * @param payload bytes originais do quadro ou comando
 * @param messageType tipo atribuído ao quadro pelo adaptador
 * @param acknowledgmentRequired indica se o protocolo pede confirmação deste quadro
 */
public record ProtocolFrame(
        String deviceIdentity,
        byte[] payload,
        String messageType,
        boolean acknowledgmentRequired
) {

    public ProtocolFrame {
        Objects.requireNonNull(deviceIdentity, "deviceIdentity");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(messageType, "messageType");

        payload = Arrays.copyOf(payload, payload.length);
    }

    public boolean isLogin() { return "LOGIN".equals(messageType); }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
