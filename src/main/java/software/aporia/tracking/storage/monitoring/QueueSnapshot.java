package software.aporia.tracking.storage.monitoring;

import java.util.Map;
import java.util.Objects;

/**
 * Fotografia imutável das filas persistidas.
 *
 * <p>Os instantes são idades em segundos, nunca datas. As contagens e idades são globais, sem recorte de
 * tenant, e o mapa de comandos é copiado na construção e exposto somente para leitura.</p>
 *
 * @param pendingPackets          pacotes brutos no estado pendente
 * @param eligiblePackets         pendentes cujo {@code available_at} já passou
 * @param retryWaitingPackets     pendentes adiados que já tiveram alguma tentativa
 * @param oldestPacketAgeSeconds  idade do pendente mais antigo, em segundos
 * @param commandsByStatus        quantidade de comandos por estado
 * @param pendingOutboxEvents     eventos de outbox ainda não publicados
 * @param oldestOutboxAgeSeconds  idade do evento pendente mais antigo, em segundos
 */
public record QueueSnapshot(
        long pendingPackets,
        long eligiblePackets,
        long retryWaitingPackets,
        double oldestPacketAgeSeconds,
        Map<String, Long> commandsByStatus,
        long pendingOutboxEvents,
        double oldestOutboxAgeSeconds) {

    /**
     * Copia o mapa para uma visão não modificável e rejeita valores nulos.
     *
     * @throws NullPointerException se o mapa for nulo ou contiver chave ou valor nulo
     */
    public QueueSnapshot {
        commandsByStatus = Map.copyOf(
                Objects.requireNonNull(commandsByStatus));
    }
}
