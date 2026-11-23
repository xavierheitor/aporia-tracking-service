
package software.aporia.tracking.worker;

/**
 * Sinaliza uma condição de processamento que permite desfazer os efeitos parciais e reagendar o
 * pacote.
 */
public class RetryableProcessingException
        extends RuntimeException {

    /** Versão da forma serializada desta exceção. */
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** Motivo estável que acompanha a falha de processamento. */
    private final String errorCode;

    /**
     * Preserva a causa operacional usada no agendamento de nova tentativa.
     *
     * @param errorCode código que identifica o motivo da falha
     */
    public RetryableProcessingException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    /**
     * Expõe o motivo que acompanha o reagendamento do pacote.
     *
     * @return código estável do motivo registrado no pacote
     */
    public String getErrorCode() {
        return errorCode;
    }
}
