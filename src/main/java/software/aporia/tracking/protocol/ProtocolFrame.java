
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

    /**
     * Copia os bytes emitidos pela sessão antes que seu buffer volte a receber dados.
     *
     * @param deviceIdentity identidade declarada pelo rastreador no protocolo
     * @param payload bytes do conteúdo da mensagem
     * @param messageType tipo declarado no cabeçalho da mensagem
     * @param acknowledgmentRequired indica se o protocolo solicita confirmação
     */
    public ProtocolFrame {
        Objects.requireNonNull(deviceIdentity, "deviceIdentity");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(messageType, "messageType");

        payload = Arrays.copyOf(payload, payload.length);
    }

    /**
     * Identifica a etapa de login para que o gateway escolha a resposta de admissão.
     *
     * @return verdadeiro para um quadro de login
     */
    public boolean isLogin() { return "LOGIN".equals(messageType); }

    /**
     * Entrega uma cópia dos bytes preservados no quadro.
     *
     * @return cópia dos bytes protegidos pela instância
     */
    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
