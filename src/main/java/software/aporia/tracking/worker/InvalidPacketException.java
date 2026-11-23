package software.aporia.tracking.worker;

/**
 * Identifica uma falha de validação do pacote por um código que pode ser registrado no
 * processamento.
 */
public class InvalidPacketException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

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
