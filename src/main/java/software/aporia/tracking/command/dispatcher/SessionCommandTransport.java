
package software.aporia.tracking.command.dispatcher;

import java.util.Optional;
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
    public Optional<Endpoint> connect(UUID tenantId, UUID deviceId) {
        return sessions.find(tenantId, deviceId).filter(DeviceSession::isConnected)
                .map(session -> session);
    }
}
