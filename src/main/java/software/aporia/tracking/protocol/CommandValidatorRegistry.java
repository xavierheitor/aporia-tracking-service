package software.aporia.tracking.protocol;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class CommandValidatorRegistry {

    private final Map<String, CommandValidator> validators;

    public CommandValidatorRegistry(
            Collection<? extends CommandValidator> registered
    ) {
        Objects.requireNonNull(registered);
        Map<String, CommandValidator> map = new HashMap<>();

        for (CommandValidator validator : registered) {
            Objects.requireNonNull(validator);

            String protocol = Objects.requireNonNull(validator.protocolId());

            if (protocol.isBlank()) {
                throw new IllegalArgumentException(
                        "INVALID_PROTOCOL_ID"
                );
            }

            if (map.putIfAbsent(protocol, validator) != null) {
                throw new IllegalArgumentException(
                        "DUPLICATE_COMMAND_VALIDATOR: " + protocol
                );
            }
        }

        validators = Map.copyOf(map);
    }

    public void validate(String protocol, byte[] payload) {
        CommandValidator validator = validators.get(protocol);

        if (validator == null) {
            throw new IllegalArgumentException(
                    "UNSUPPORTED_COMMAND_PROTOCOL: " + protocol
            );
        }

        validator.validate(payload);
    }

    public void validate(
            String protocol,
            byte[] payload,
            String expectedIdentity
    ) {
        CommandValidator validator = validators.get(protocol);

        if (validator == null) {
            throw new IllegalArgumentException(
                    "UNSUPPORTED_COMMAND_PROTOCOL: " + protocol
            );
        }

        validator.validate(payload, expectedIdentity);
    }

}
