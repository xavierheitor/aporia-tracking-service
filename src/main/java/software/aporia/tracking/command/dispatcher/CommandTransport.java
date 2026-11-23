package software.aporia.tracking.command.dispatcher;

import java.util.Optional;
import java.io.IOException;
import java.util.UUID;

public interface CommandTransport {

    interface Endpoint {
        String protocol();
        String identity();
        void send(byte[] payload) throws IOException;
    }

    Optional<Endpoint> connect(UUID tenantId, UUID deviceId);

    default boolean isConnected(
            UUID tenantId,
            UUID deviceId
    ) {
        return connect(tenantId, deviceId).isPresent();
    }

    default void send(
            UUID tenantId,
            UUID deviceId,
            byte[] payload
    ) throws IOException {
        connect(tenantId, deviceId).orElseThrow(() -> new IOException("DEVICE_SESSION_NOT_AVAILABLE"))
                .send(payload);
    }
}
