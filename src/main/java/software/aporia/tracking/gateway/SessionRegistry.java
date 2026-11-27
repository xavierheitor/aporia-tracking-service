package software.aporia.tracking.gateway;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mantém a sessão atual por tenant e dispositivo e permite substituir uma
 * conexão quando o rastreador reconecta.
 *
 * <p> Invariantes: existe no máximo uma sessão por par (tenant, dispositivo), e a comparação é feita pela
 * instância de {@link DeviceSession}, não pelo token. {@link #register} invalida a sessão anterior, mas não
 * fecha seu socket; quem recebe o valor de retorno é responsável por fechá-lo. </p>
 *
 * <p> Concorrência: o mapa é um {@link ConcurrentHashMap}. O {@code compute} executa a ativação e a
 * publicação sob o bloqueio da chave, e {@code remove(chave, sessão)} só remove se a instância ainda for a
 * atual. Assim, uma desconexão atrasada da conexão antiga não remove a reconexão. </p>
 */
public final class SessionRegistry {

    /**
     * Identifica a entrada de uma sessão pela combinação de tenant e dispositivo.
     *
     * @param tenantId tenant proprietário do equipamento
     * @param deviceId equipamento cuja conexão ocupa a entrada
     */
    private record DeviceKey(
            UUID tenantId,
            UUID deviceId) {
    }

    /** Registro que associa tenant e dispositivo à sessão atual. */
    private final Map<DeviceKey, DeviceSession> sessions = new ConcurrentHashMap<>();

    /**
     * Instala a nova sessão sem callback de ativação e devolve a anterior, quando houver. A anterior é
     * invalidada, mas o socket só é fechado pelo chamador.
     *
     * @param session sessão escolhida para a operação
     * @return sessão substituída, se havia outra conexão para o equipamento
     * @throws NullPointerException se a sessão for nula
     */
    public Optional<DeviceSession> register(DeviceSession session) {
        return register(session, () -> {
        });
    }

    /**
     * Publica a sessão local na mesma ordem em que as conexões deste dispositivo são admitidas. O callback
     * {@code activate} roda dentro de {@code compute}, antes da invalidação da anterior, de modo que a
     * ativação no banco e a publicação local não se intercalam com outra admissão do mesmo par.
     *
     * <p> Se {@code activate} lançar uma exceção, o mapeamento não muda: a sessão anterior permanece atual e
     * a nova não é publicada. </p>
     *
     * @param session  sessão escolhida para a operação
     * @param activate ação executada antes de publicar a sessão, como a ativação
     *                 do token no cadastro
     * @return sessão substituída, se havia outra conexão para o equipamento
     * @throws NullPointerException se a sessão ou o callback forem nulos
     * @throws RuntimeException     propagada do callback, sem publicar a sessão
     */
    Optional<DeviceSession> register(DeviceSession session, Runnable activate) {

        Objects.requireNonNull(session);

        DeviceKey key = new DeviceKey(
                session.tenantId(),
                session.deviceId());

        AtomicReference<DeviceSession> replaced = new AtomicReference<>();

        sessions.compute(key, (_, previous) -> {

            // A chave fica bloqueada durante a ativação e a troca, serializando reconexões.
            activate.run();
            // Registrar a mesma instância de novo é inofensivo; só outra conexão é invalidada.
            if (previous != null && previous != session) {
                previous.invalidate();
                replaced.set(previous);
            }

            return session;
        });

        return Optional.ofNullable(replaced.get());
    }

    /**
     * Consulta a sessão do dispositivo dentro do tenant informado. Não valida os identificadores e não filtra
     * sessões invalidadas ou desconectadas.
     *
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @return sessão registrada para o equipamento, se houver
     */
    public Optional<DeviceSession> find(
            UUID tenantId,
            UUID deviceId) {
        return Optional.ofNullable(
                sessions.get(new DeviceKey(tenantId, deviceId)));
    }

    /**
     * Remove a sessão apenas se ela ainda ocupar a entrada, preservando uma eventual reconexão. A sessão
     * recebida é invalidada mesmo quando já foi substituída; a remoção condicional é que não atinge a
     * instância nova.
     *
     * @param session sessão escolhida para a operação
     * @throws NullPointerException se a sessão for nula
     */
    public void unregister(DeviceSession session) {

        Objects.requireNonNull(session);

        DeviceKey key = new DeviceKey(
                session.tenantId(),
                session.deviceId());

        // Invalida antes de remover para que uma escrita concorrente falhe fechada.
        session.invalidate();
        // A comparação da instância preserva a sessão nova se a antiga terminar depois
        // da reconexão.
        sessions.remove(key, session);
    }

    /**
     * Obtém uma cópia das sessões registradas para a rodada de envio. A cópia não filtra sessões invalidadas
     * nem garante que o socket continue aberto.
     *
     * @return cópia das sessões registradas no momento da consulta
     */
    public List<DeviceSession> onlineSessions() {
        return List.copyOf(sessions.values());
    }

    /**
     * Conta as entradas atualmente registradas para diagnóstico e verificação do
     * ciclo de
     * conexão.
     *
     * @return quantidade de sessões registradas
     */
    public int size() {
        return sessions.size();
    }

    /**
     * Compara a instância recebida com a sessão atualmente registrada para o mesmo equipamento. Um resultado
     * falso pode significar sessão removida ou substituída por outra conexão.
     *
     * @param session sessão escolhida para a operação
     * @return verdadeiro se a instância ainda é a sessão registrada sob a chave
     * @throws NullPointerException se a sessão for nula
     */
    public boolean isCurrent(DeviceSession session) {

        Objects.requireNonNull(session);

        DeviceKey key = new DeviceKey(
                session.tenantId(),
                session.deviceId());

        return sessions.get(key) == session;
    }
}
