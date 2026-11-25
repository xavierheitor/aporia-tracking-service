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
 * conexão quando o
 * rastreador reconecta.
 */
public final class SessionRegistry {

    /**
     * * Identifica a entrada de uma sessão pela combinação de tenant e dispositivo.
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
     * Instala a nova sessão e devolve a anterior, quando houver, para que o
     * chamador feche o
     * socket substituído.
     *
     * @param session sessão escolhida para a operação
     * @return sessão substituída, se havia outra conexão para o equipamento
     */
    public Optional<DeviceSession> register(DeviceSession session) {
        return register(session, () -> {
        });
    }

    /**
     * Publica a sessão local na mesma ordem em que as conexões deste dispositivo
     * são admitidas.
     */
    Optional<DeviceSession> register(DeviceSession session, Runnable activate) {

        Objects.requireNonNull(session);

        DeviceKey key = new DeviceKey(
                session.tenantId(),
                session.deviceId());

        AtomicReference<DeviceSession> replaced = new AtomicReference<>();

        sessions.compute(key, (_, previous) -> {

            activate.run();
            if (previous != null && previous != session) {
                previous.invalidate();
                replaced.set(previous);
            }

            return session;
        });

        return Optional.ofNullable(replaced.get());
    }

    /**
     * Consulta a sessão do dispositivo dentro do tenant informado.
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
     * Remove a sessão apenas se ela ainda ocupar a entrada, preservando uma
     * eventual reconexão.
     *
     * @param session sessão escolhida para a operação
     */
    public void unregister(DeviceSession session) {

        Objects.requireNonNull(session);

        DeviceKey key = new DeviceKey(
                session.tenantId(),
                session.deviceId());

        session.invalidate();
        // A comparação da instância preserva a sessão nova se a antiga terminar depois
        // da reconexão.
        sessions.remove(key, session);
    }

    /**
     * Obtém uma cópia das sessões registradas para a rodada de envio.
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
     * Compara a instância recebida com a sessão atualmente registrada para o mesmo
     * equipamento.
     *
     * @param session sessão escolhida para a operação
     * @return verdadeiro se a instância ainda é a sessão registrada sob a chave
     */
    public boolean isCurrent(DeviceSession session) {

        Objects.requireNonNull(session);

        DeviceKey key = new DeviceKey(
                session.tenantId(),
                session.deviceId());

        return sessions.get(key) == session;
    }
}
