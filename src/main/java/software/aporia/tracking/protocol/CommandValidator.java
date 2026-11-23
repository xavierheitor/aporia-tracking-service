package software.aporia.tracking.protocol;

/** Define a validação de um payload de comando segundo o protocolo do dispositivo. */
public interface CommandValidator {

    /**
     * Identifica a gramática de comandos aceita pelo validador.
     *
     * @return identificador do protocolo desta implementação
     */
    String protocolId();

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
        validate(payload);
    }
}