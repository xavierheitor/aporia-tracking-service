package software.aporia.tracking.worker;

/**
 * Identifica uma falha de validação do pacote por um código que pode ser registrado no
 * processamento.
 *
 * <p>É uma falha permanente: o worker grava o código na recepção e não agenda nova tentativa. O código é a
 * única informação usada na decisão; a mensagem e a causa ficam restritas ao diagnóstico local e não são
 * persistidas no pacote.</p>
 */
public class InvalidPacketException extends RuntimeException {

    /** Versão da forma serializada desta exceção. */
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** Motivo estável que acompanha a falha de processamento. */
    private final String errorCode;

    /**
     * Preserva o código usado para identificar a rejeição permanente.
     *
     * @param errorCode código que identifica o motivo da falha
     */
    public InvalidPacketException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    /**
     * Preserva o motivo da rejeição e a exceção original do decodificador.
     *
     * @param errorCode código que identifica o motivo da falha
     * @param cause falha original preservada para diagnóstico
     */
    public InvalidPacketException(
            String errorCode,
            Throwable cause
    ) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    /**
     * Expõe o motivo que o worker registra no pacote rejeitado.
     *
     * @return código estável do motivo registrado no pacote
     */
    public String getErrorCode() {
        return errorCode;
    }
}
