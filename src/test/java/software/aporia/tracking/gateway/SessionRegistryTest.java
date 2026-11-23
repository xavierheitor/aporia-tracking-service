package software.aporia.tracking.gateway;

import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** Verifica o isolamento das sessões por tenant e a remoção condicional após reconexão. */
class SessionRegistryTest {

    /**
     * Confere a recuperação da sessão registrada para o equipamento.
     */
    @Test
    void shouldRegisterAndFindSession() {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        SessionRegistry registry = new SessionRegistry();

        DeviceSession session = new DeviceSession(
                tenantId,
                deviceId,
                "suntech",
                "1234567890",
                new Socket()
        );

        registry.register(session);

        assertSame(
                session,
                registry.find(tenantId, deviceId).orElseThrow()
        );

        assertEquals(1, registry.size());
    }

    /**
     * Verifica o isolamento da chave de sessão entre tenants.
     */
    @Test
    void shouldIsolateTenants() {

        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        SessionRegistry registry = new SessionRegistry();

        registry.register(new DeviceSession(
                tenantA,
                deviceId,
                "suntech",
                "1234567890",
                new Socket()
        ));

        assertTrue(
                registry.find(tenantB, deviceId).isEmpty()
        );
    }

    /**
     * Desconecta a sessão antiga após uma reconexão e confere que a nova permanece registrada.
     */
    @Test
    void shouldNotRemoveNewSessionWhenOldDisconnects() {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        SessionRegistry registry = new SessionRegistry();

        DeviceSession oldSession = new DeviceSession(
                tenantId, deviceId, "suntech", "1234567890", new Socket()
        );

        DeviceSession newSession = new DeviceSession(
                tenantId, deviceId, "suntech", "1234567890", new Socket()
        );

        registry.register(oldSession);
        registry.register(newSession);

        registry.unregister(oldSession);

        assertSame(
                newSession,
                registry.find(tenantId, deviceId).orElseThrow()
        );
    }

    /**
     * Remove a sessão e consulta a ausência da entrada.
     */
    @Test
    void shouldUnregisterSession() {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        SessionRegistry registry = new SessionRegistry();

        DeviceSession session = new DeviceSession(
                tenantId, deviceId, "suntech", "1234567890", new Socket()
        );

        registry.register(session);
        registry.unregister(session);

        assertTrue(
                registry.find(tenantId, deviceId).isEmpty()
        );

        assertEquals(0, registry.size());
    }

    /**
     * Registra uma reconexão e verifica a invalidação do socket lógico anterior.
     */
    @Test
    void shouldInvalidatePreviousSessionOnReconnect() {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        SessionRegistry registry = new SessionRegistry();

        DeviceSession oldSession = new DeviceSession(
                tenantId,
                deviceId,
                "suntech",
                "1234567890",
                new Socket()
        );

        DeviceSession newSession = new DeviceSession(
                tenantId,
                deviceId,
                "suntech",
                "1234567890",
                new Socket()
        );

        registry.register(oldSession);
        registry.register(newSession);

        assertFalse(oldSession.isConnected());

        assertSame(
                newSession,
                registry.find(tenantId, deviceId).orElseThrow()
        );
    }

    /**
     * Confere a invalidação da sessão removida pelo registro.
     */
    @Test
    void shouldInvalidateSessionOnUnregister() {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        SessionRegistry registry = new SessionRegistry();

        DeviceSession session = new DeviceSession(
                tenantId,
                deviceId,
                "suntech",
                "1234567890",
                new Socket()
        );

        registry.register(session);
        registry.unregister(session);

        assertFalse(session.isConnected());

        assertTrue(
                registry.find(tenantId, deviceId).isEmpty()
        );
    }

    /**
     * Confere que o chamador recebe a sessão substituída para poder fechá-la.
     */
    @Test
    void shouldReturnReplacedSession() {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        SessionRegistry registry = new SessionRegistry();

        DeviceSession oldSession = new DeviceSession(
                tenantId,
                deviceId,
                "suntech",
                "1234567890",
                new Socket()
        );

        DeviceSession newSession = new DeviceSession(
                tenantId,
                deviceId,
                "suntech",
                "1234567890",
                new Socket()
        );

        assertTrue(registry.register(oldSession).isEmpty());

        var replaced = registry.register(newSession);

        assertTrue(replaced.isPresent());
        assertSame(oldSession, replaced.orElseThrow());

        assertFalse(registry.isCurrent(oldSession));
        assertTrue(registry.isCurrent(newSession));
    }

    /**
     * Verifica que uma sessão removida deixa de ser a instância atual.
     */
    @Test
    void shouldNotConsiderRemovedSessionCurrent() {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        SessionRegistry registry = new SessionRegistry();

        DeviceSession session = new DeviceSession(
                tenantId,
                deviceId,
                "suntech",
                "1234567890",
                new Socket()
        );

        assertFalse(registry.isCurrent(session));

        registry.register(session);

        assertTrue(registry.isCurrent(session));

        registry.unregister(session);

        assertFalse(registry.isCurrent(session));
    }

}
