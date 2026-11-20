package software.aporia.tracking.core;

public class InvalidPacketException extends RuntimeException {

    private final String errorCode;

    public InvalidPacketException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public InvalidPacketException(
            String errorCode,
            Throwable cause
    ) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
