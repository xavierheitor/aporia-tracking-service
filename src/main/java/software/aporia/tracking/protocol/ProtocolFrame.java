
package software.aporia.tracking.protocol;

import java.util.Arrays;
import java.util.Objects;

public record ProtocolFrame(
        String deviceIdentity,
        byte[] payload,
        String messageType,
        boolean acknowledgmentRequired
) {

    public ProtocolFrame {
        Objects.requireNonNull(deviceIdentity, "deviceIdentity");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(messageType, "messageType");

        payload = Arrays.copyOf(payload, payload.length);
    }

    public boolean isLogin() { return "LOGIN".equals(messageType); }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
