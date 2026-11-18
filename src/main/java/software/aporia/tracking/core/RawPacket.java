package software.aporia.tracking.core;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record RawPacket(
        UUID id,
        String protocol,
        String deviceIdentity,
        byte[] payload,
        Instant receivedAt
        ) {

    public RawPacket {
        Objects.requireNonNull(id, "Packet ID is required");
        Objects.requireNonNull(receivedAt, "Reception time is required");

        if (protocol == null || protocol.isBlank()) {
            throw new IllegalArgumentException("Protocol is required");
        }

        if (deviceIdentity == null || deviceIdentity.isBlank()) {
            throw new IllegalArgumentException("Device identity is required");
        }

        Objects.requireNonNull(payload, "Payload is required");

        if (payload.length == 0 || payload.length > 65536) {
            throw new IllegalArgumentException("Invalid payload size");
        }

        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof RawPacket other
                && Objects.equals(id, other.id)
                && Objects.equals(protocol, other.protocol)
                && Objects.equals(deviceIdentity, other.deviceIdentity)
                && Arrays.equals(payload, other.payload)
                && Objects.equals(receivedAt, other.receivedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                id, protocol, deviceIdentity,
                Arrays.hashCode(payload), receivedAt
        );
    }
}
