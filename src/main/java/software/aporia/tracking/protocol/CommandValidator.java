package software.aporia.tracking.protocol;

import org.jspecify.annotations.NonNull;

/**
 * Define a validação sintática dos bytes de comando segundo o protocolo do dispositivo.
 * Um formato válido ainda depende das permissões e da política de execução do chamador.
 *
 * <p>A validação não autoriza a operação: identidade da sessão, permissões e política continuam com o
 * dispatcher. A implementação pode ser reutilizada entre comandos e não deve manter estado mutável
 * compartilhado entre chamadas.</p>
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
     * @throws IllegalArgumentException se o payload não seguir a gramática do protocolo
     */
    void validate(byte[] payload);

    /**
     * Verifica o formato do comando. A sobrecarga com identidade permite ao protocolo conferir
     * também o destinatário.
     *
     * @param payload bytes do conteúdo da mensagem
     * @param expectedIdentity identidade de referência do equipamento
     * @throws IllegalArgumentException se o payload não seguir a gramática do protocolo ou se o
     * destinatário não corresponder à identidade esperada
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
