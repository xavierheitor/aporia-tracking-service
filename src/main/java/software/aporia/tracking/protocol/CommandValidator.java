package software.aporia.tracking.protocol;

import org.jspecify.annotations.NonNull;

/**
 * Define a validação sintática dos bytes de comando segundo o protocolo do dispositivo.
 * Um formato válido ainda depende das permissões e da política de execução do chamador.
 */
public interface CommandValidator {

    /**
     * Identifica a gramática de comandos aceita pelo validador.
     *
     * @return identificador do protocolo desta implementação
     */
    @NonNull String protocolId();

    /**
     * Verifica o formato do comando. A sobrecarga com identidade permite ao protocolo conferir
     * também o destinatário.
     *
     * @param payload bytes do conteúdo da mensagem
     */
    void validate(byte[] payload);

    /**
     * Verifica o formato do comando. A sobrecarga com identidade permite ao protocolo conferir
     * também o destinatário.
     *
     * @param payload bytes do conteúdo da mensagem
     * @param expectedIdentity identidade de referência do equipamento
     */
    default void validate(
            byte[] payload,
            String expectedIdentity
    ) {
        // A implementação padrão só confere a sintaxe. Protocolos com destinatário
        // no payload precisam sobrescrever esta versão para conferir a identidade.
        validate(payload);
    }
}
