
package software.aporia.tracking.command.dispatcher;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

import software.aporia.tracking.gateway.DeviceSession;
import software.aporia.tracking.gateway.SessionRegistry;

public final class SessionCommandTransport
        implements CommandTransport {

    private final SessionRegistry sessions;

    public SessionCommandTransport(
            SessionRegistry sessions
    ) {
        this.sessions = Objects.requireNonNull(sessions);
    }

    @Override
    public boolean isConnected(
            UUID tenantId,
            UUID deviceId
    ) {
        return sessions.find(tenantId, deviceId)
                .filter(DeviceSession::isConnected)
                .isPresent();
    }

    @Override
    public void send(
            UUID tenantId,
            UUID deviceId,
            byte[] payload
    ) throws IOException {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(payload);

        DeviceSession session = sessions
                .find(tenantId, deviceId)
                .filter(DeviceSession::isConnected)
                .orElseThrow(
                        () -> new IOException(
                                "DEVICE_SESSION_NOT_AVAILABLE"
                        )
                );

        // Confirma que a sessão continua sendo a atual.
        if (!sessions.isCurrent(session)) {
            throw new IOException(
                    "DEVICE_SESSION_REPLACED"
            );
        }

        session.send(payload);
    }
}
