package software.aporia.tracking.gateway;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class SessionRegistry {

    private record DeviceKey(
            UUID tenantId,
            UUID deviceId
            ) {}

    private final Map<DeviceKey, DeviceSession> sessions
            = new ConcurrentHashMap<>();

    public Optional<DeviceSession> register(DeviceSession session) {

        Objects.requireNonNull(session);

        DeviceKey key = new DeviceKey(
                session.tenantId(),
                session.deviceId()
        );

        AtomicReference<DeviceSession> replaced
                = new AtomicReference<>();

        sessions.compute(key, (ignored, previous) -> {

            if (previous != null && previous != session) {
                previous.invalidate();
                replaced.set(previous);
            }

            return session;
        });

        return Optional.ofNullable(replaced.get());
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

        session.invalidate();
        sessions.remove(key, session);
    }

    public List<DeviceSession> onlineSessions() {
        return List.copyOf(sessions.values());
    }

    public int size() {
        return sessions.size();
    }

    public boolean isCurrent(DeviceSession session) {

        Objects.requireNonNull(session);

        DeviceKey key = new DeviceKey(
                session.tenantId(),
                session.deviceId()
        );

        return sessions.get(key) == session;
    }
}
