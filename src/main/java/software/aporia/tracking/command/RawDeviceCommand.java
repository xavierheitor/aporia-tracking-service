package software.aporia.tracking.command;

import java.util.Objects;
import java.util.UUID;

public record RawDeviceCommand(
        UUID commandId,
        UUID tenantId,
        UUID deviceId,
        String protocol,
        byte[] payload
        ) {

    private static final int MAX_COMMAND_SIZE = 8192;

    public RawDeviceCommand {

        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(payload, "payload");

        if (protocol.isBlank()) {
            throw new IllegalArgumentException(
                    "INVALID_PROTOCOL"
            );
        }

        if (payload.length == 0
                || payload.length > MAX_COMMAND_SIZE) {
            throw new IllegalArgumentException(
                    "INVALID_COMMAND_SIZE"
            );
        }

        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
