
package software.aporia.tracking.protocol;

import java.util.Arrays;
import java.util.Objects;

/**
 * Representa um quadro completo emitido pela sessão de protocolo, com a identidade e a indicação de
 * confirmação ao rastreador.
 *
 * <p>O payload é copiado na construção e devolvido como cópia, então o buffer da sessão pode ser
 * reaproveitado sem alterar o quadro. A instância é imutável e pode ser repassada entre etapas sem risco de
 * alteração. A confirmação indicada aqui apenas sinaliza que o protocolo pede ACK; ela não comprova entrega
 * nem execução. O payload pode ser vazio, como no login Teltonika.</p>
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
     * @param messageType tipo atribuído ao quadro pelo adaptador
     * @param acknowledgmentRequired indica se o protocolo solicita confirmação
     * @throws NullPointerException se identidade, payload ou tipo forem nulos
     */
    public ProtocolFrame {
        // Identidade e tipo sempre existem; o payload vazio é permitido no login.
        Objects.requireNonNull(deviceIdentity, "deviceIdentity");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(messageType, "messageType");

        // Arrays são mutáveis: copiar impede que o buffer do chamador altere o quadro.
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
