
package software.aporia.tracking.worker;

public class RetryableProcessingException
        extends RuntimeException {

    private final String errorCode;

    public RetryableProcessingException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
