
package software.aporia.tracking.worker;

/**
 * Sinaliza uma condição de processamento que permite desfazer os efeitos parciais e reagendar o
 * pacote.
 */
public class RetryableProcessingException
        extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public RetryableProcessingException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
