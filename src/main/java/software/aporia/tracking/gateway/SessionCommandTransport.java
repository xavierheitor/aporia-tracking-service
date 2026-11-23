
package software.aporia.tracking.gateway;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import software.aporia.tracking.command.dispatcher.CommandTransport;

/** Expõe ao dispatcher as sessões TCP ativas no registro do gateway. */
public final class SessionCommandTransport
        implements CommandTransport {

    private final SessionRegistry sessions;

    /** Usa o registro do gateway para localizar o socket atual de cada equipamento. */
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
