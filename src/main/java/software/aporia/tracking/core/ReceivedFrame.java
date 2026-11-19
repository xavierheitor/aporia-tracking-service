package software.aporia.tracking.core;

import java.util.Objects;

public record ReceivedFrame(
        String protocol,
        String identity,
        byte[] payload
        ) {

    public ReceivedFrame {
        if (protocol == null || protocol.isEmpty()) {
            throw new IllegalArgumentException("Invalid protocol");
        }
        if (identity == null || identity.isEmpty()) {
            throw new IllegalArgumentException("Invalid identity");
        }

        Objects.requireNonNull(payload, "payload");

        if (payload.length == 0 || payload.length > 65536) {
            throw new IllegalArgumentException("Invalid payload size");
        }

        payload = payload.clone();

    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
