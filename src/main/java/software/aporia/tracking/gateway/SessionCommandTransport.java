
package software.aporia.tracking.gateway;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import software.aporia.tracking.command.dispatcher.CommandTransport;

/**
 * Expõe ao dispatcher as sessões TCP ativas no registro do gateway.
 *
 * <p> Colaboradores: consulta o {@link SessionRegistry} e devolve a {@link DeviceSession} atual como {@link
 * CommandTransport.Endpoint}. Cada chamada é uma fotografia: a sessão pode ser substituída ou invalidada
 * antes da escrita, e o próprio endpoint recusa o envio nesse caso. </p>
 *
 * <p> Concorrência: não mantém estado mutável nem bloqueios próprios. A coerência entre threads depende do
 * registro, que usa operações atômicas. O filtro por tenant e dispositivo impede que a sessão de outro tenant
 * seja devolvida. </p>
 */
public final class SessionCommandTransport
        implements CommandTransport {

    /** Registro que associa tenant e dispositivo à sessão atual. */
    private final SessionRegistry sessions;

    /**
     * Usa o registro do gateway para localizar o socket atual de cada equipamento.
     *
     * @param sessions registro de sessões ativas do gateway
     * @throws NullPointerException se o registro for nulo
     */
    public SessionCommandTransport(
            SessionRegistry sessions) {
        this.sessions = Objects.requireNonNull(sessions);
    }

    /**
     * Seleciona a sessão atual do dispositivo dentro do tenant para esta tentativa de envio. Não reserva a
     * sessão: a conexão pode ser substituída antes da escrita, que então falha com {@code
     * DEVICE_DISCONNECTED}.
     *
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @return canal da sessão disponível, ou vazio se o dispositivo estiver offline
     */
    @Override
    public Optional<Endpoint> connect(UUID tenantId, UUID deviceId) {
        // A sessão é apenas selecionada; a validade é conferida novamente na escrita.
        // A conversão preserva a instância, sem copiar o socket.
        return sessions.find(tenantId, deviceId).filter(session -> session.isConnected())
                .map(session -> session);
    }
}
