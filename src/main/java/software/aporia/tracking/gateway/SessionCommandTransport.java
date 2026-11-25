
package software.aporia.tracking.gateway;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import software.aporia.tracking.command.dispatcher.CommandTransport;

/** Expõe ao dispatcher as sessões TCP ativas no registro do gateway. */
public final class SessionCommandTransport
        implements CommandTransport {

    /** Registro que associa tenant e dispositivo à sessão atual. */
    private final SessionRegistry sessions;

    /**
     * Usa o registro do gateway para localizar o socket atual de cada equipamento.
     *
     * @param sessions registro de sessões ativas do gateway
     */
    public SessionCommandTransport(
            SessionRegistry sessions) {
        this.sessions = Objects.requireNonNull(sessions);
    }

    /**
     * Seleciona a sessão conectada do dispositivo dentro do tenant para esta
     * tentativa de envio.
     *
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @return canal da sessão disponível, ou vazio se o dispositivo estiver offline
     */
    @Override
    public Optional<Endpoint> connect(UUID tenantId, UUID deviceId) {
        return sessions.find(tenantId, deviceId).filter(session -> session.isConnected())
                .map(session -> session);
    }
}
