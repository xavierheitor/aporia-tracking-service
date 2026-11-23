package software.aporia.tracking.gateway;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mantém a sessão atual por tenant e dispositivo e permite substituir uma conexão quando o
 * rastreador reconecta.
 */
public final class SessionRegistry {

    private record DeviceKey(
            UUID tenantId,
            UUID deviceId
            ) {}

    private final Map<DeviceKey, DeviceSession> sessions
            = new ConcurrentHashMap<>();

    /**
     * Instala a nova sessão e devolve a anterior, quando houver, para que o chamador feche o
     * socket substituído.
     */
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

    /** Consulta a sessão do dispositivo dentro do tenant informado. */
    public Optional<DeviceSession> find(
            UUID tenantId,
            UUID deviceId
    ) {
        return Optional.ofNullable(
                sessions.get(new DeviceKey(tenantId, deviceId))
        );
    }

    /** Remove a sessão apenas se ela ainda ocupar a entrada, preservando uma eventual reconexão. */
    public void unregister(DeviceSession session) {

        Objects.requireNonNull(session);

        DeviceKey key = new DeviceKey(
                session.tenantId(),
                session.deviceId()
        );

        session.invalidate();
        sessions.remove(key, session);
    }

    /** Obtém uma cópia das sessões registradas para a rodada de envio. */
    public List<DeviceSession> onlineSessions() {
        return List.copyOf(sessions.values());
    }

    public int size() {
        return sessions.size();
    }

    /** Compara a instância recebida com a sessão atualmente registrada para o mesmo equipamento. */
    public boolean isCurrent(DeviceSession session) {

        Objects.requireNonNull(session);

        DeviceKey key = new DeviceKey(
                session.tenantId(),
                session.deviceId()
        );

        return sessions.get(key) == session;
    }
}
