package software.aporia.tracking.gateway;

import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Protege o registro da sessão atual por tenant e dispositivo: a chave isola tenants, a
 * reconexão invalida a sessão anterior e a remoção é condicional à instância registrada.
 *
 * <p>
 * O registro é compartilhado entre o handler TCP e o dispatcher, por isso as operações usam
 * {@link java.util.concurrent.ConcurrentHashMap}. Os testes não abrem conexão de rede: um
 * {@link Socket} não conectado apenas compõe a identidade da sessão, e a concorrência fica
 * restrita às operações atômicas do mapa.
 * </p>
 */
class SessionRegistryTest {

    /**
     * Cenário: registro vazio e uma sessão de tenant e dispositivo conhecidos. Ação: registrar
     * e consultar. Resultado: a mesma instância é encontrada e ocupa uma entrada.
     */
    @Test
    void shouldRegisterAndFindSession() {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        SessionRegistry registry = new SessionRegistry();

        // O socket não é conectado; a chave do registro depende apenas de tenant e dispositivo.
        DeviceSession session = new DeviceSession(
                tenantId,
                deviceId,
                "suntech",
                "1234567890",
                new Socket()
        );

        registry.register(session);

        // A consulta devolve a própria instância publicada, não uma cópia.
        assertSame(
                session,
                registry.find(tenantId, deviceId).orElseThrow()
        );

        assertEquals(1, registry.size());
    }

    /**
     * Cenário: sessão registrada para um tenant. Ação: consultar o mesmo dispositivo em outro
     * tenant. Resultado: nenhuma sessão encontrada, preservando o isolamento da chave.
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

        // Mesmo deviceId não basta: o tenant faz parte da chave de busca.
        assertTrue(
                registry.find(tenantB, deviceId).isEmpty()
        );
    }

    /**
     * Cenário: duas sessões do mesmo dispositivo, com a nova substituindo a antiga. Ação:
     * desregistrar a antiga. Resultado: a sessão nova permanece registrada.
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
        // A segunda publicação substitui a entrada e invalida a sessão antiga.
        registry.register(newSession);

        // A remoção compara a instância: a antiga já não ocupa a chave, então nada é removido.
        registry.unregister(oldSession);

        assertSame(
                newSession,
                registry.find(tenantId, deviceId).orElseThrow()
        );
    }

    /**
     * Cenário: sessão registrada. Ação: desregistrar a própria instância. Resultado: a chave é
     * liberada e o registro fica vazio.
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

        // A entrada só é removida quando a instância consultada é a atual.
        assertTrue(
                registry.find(tenantId, deviceId).isEmpty()
        );

        assertEquals(0, registry.size());
    }

    /**
     * Cenário: sessão antiga registrada e uma reconexão do mesmo dispositivo. Ação: registrar a
     * sessão nova. Resultado: a antiga é invalidada e a nova passa a responder pela chave.
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

        // O registro aplica invalidate() à sessão anterior antes de publicar a nova.
        assertFalse(oldSession.isConnected());

        assertSame(
                newSession,
                registry.find(tenantId, deviceId).orElseThrow()
        );
    }

    /**
     * Cenário: sessão registrada. Ação: desregistrar. Resultado: a sessão é invalidada e a
     * entrada correspondente desaparece.
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

        // Desregistrar invalida a sessão mesmo antes de o handler encerrar a conexão.
        assertFalse(session.isConnected());

        assertTrue(
                registry.find(tenantId, deviceId).isEmpty()
        );
    }

    /**
     * Cenário: registro vazio e duas sessões do mesmo dispositivo. Ação: registrar a primeira e
     * depois a segunda. Resultado: o primeiro registro não devolve substituída, o segundo devolve
     * a antiga, e {@code isCurrent} aponta apenas para a nova.
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

        // O primeiro registro não tem sessão anterior para devolver.
        assertTrue(registry.register(oldSession).isEmpty());

        var replaced = registry.register(newSession);

        // O chamador recebe a instância substituída para poder fechar o socket antigo.
        assertTrue(replaced.isPresent());
        assertSame(oldSession, replaced.orElseThrow());

        // isCurrent compara a instância registrada, não apenas a chave tenant/dispositivo.
        assertFalse(registry.isCurrent(oldSession));
        assertTrue(registry.isCurrent(newSession));
    }

    /**
     * Cenário: sessão ainda não registrada, depois registrada e por fim desregistrada. Ação:
     * consultar {@code isCurrent} em cada etapa. Resultado: falso, verdadeiro e falso.
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

        // Antes do registro, a instância não ocupa nenhuma chave.
        assertFalse(registry.isCurrent(session));

        registry.register(session);

        assertTrue(registry.isCurrent(session));

        registry.unregister(session);

        // A remoção condicional faz a mesma instância deixar de ser atual.
        assertFalse(registry.isCurrent(session));
    }

}
