package software.aporia.tracking.command.dispatcher;

import java.io.IOException;
import java.util.UUID;

public interface CommandTransport {

    boolean isConnected(
            UUID tenantId,
            UUID deviceId
    );

    void send(
            UUID tenantId,
            UUID deviceId,
            byte[] payload
    ) throws IOException;
}
