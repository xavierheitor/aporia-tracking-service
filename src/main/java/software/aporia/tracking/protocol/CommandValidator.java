package software.aporia.tracking.protocol;

public interface CommandValidator {

    String protocolId();

    void validate(byte[] payload);

    default void validate(
            byte[] payload,
            String expectedIdentity
    ) {
        validate(payload);
    }
}