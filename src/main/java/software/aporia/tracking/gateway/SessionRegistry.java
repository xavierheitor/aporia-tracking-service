package software.aporia.tracking.gateway;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionRegistry {

    private record DeviceKey(
            UUID tenantId,
            UUID deviceId
            ) {}

    private final Map<DeviceKey, DeviceSession> sessions
            = new ConcurrentHashMap<>();

    public void register(DeviceSession session) {

        Objects.requireNonNull(session);

        DeviceKey key = new DeviceKey(
                session.tenantId(),
                session.deviceId()
        );

        sessions.compute(key, (ignored, previous) -> {

            if (previous != null && previous != session) {
                previous.invalidate();
            }

            return session;
        });
    }

    public Optional<DeviceSession> find(
            UUID tenantId,
            UUID deviceId
    ) {
        return Optional.ofNullable(
                sessions.get(new DeviceKey(tenantId, deviceId))
        );
    }

    public void unregister(DeviceSession session) {

        Objects.requireNonNull(session);

        DeviceKey key = new DeviceKey(
                session.tenantId(),
                session.deviceId()
        );

        sessions.remove(key, session);

        session.invalidate();
    }

    public int size() {
        return sessions.size();
    }
}
