package software.aporia.tracking.storage;

import java.util.Objects;
import java.util.UUID;

public record RegisteredDevice(
        UUID tenantId,
        UUID deviceId,
        String protocol,
        String identity
        ) {

    public RegisteredDevice {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(protocol);
        Objects.requireNonNull(identity);
    }
}
