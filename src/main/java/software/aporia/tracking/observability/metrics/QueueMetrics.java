package software.aporia.tracking.observability.metrics;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import software.aporia.tracking.storage.monitoring.QueueSnapshot;

/**
 * Publica métricas das filas persistidas a partir do último snapshot coletado.
 *
 * <p>O snapshot é imutável e substituído atomicamente; quando ainda não houve coleta, os gauges retornam
 * {@code NaN} e {@code tracking.queue.snapshot.available} vale {@code 0}. Uma falha de coleta não apaga o
 * último snapshot: os valores continuam visíveis e a idade do snapshot aumenta. As tags de {@code
 * tracking.queue.commands} são fixas nos nove estados conhecidos, portanto a cardinalidade é limitada.</p>
 *
 * <p>O bean é criado no perfil {@code monitor}; {@link QueueMetricsCollector} agenda a atualização. A leitura
 * dos gauges é concorrente com {@link #publish(QueueSnapshot)} e não bloqueia o coletor.</p>
 */
@NullMarked
public final class QueueMetrics {

    /**
     * Snapshot publicado e o instante monotônico da publicação.
     *
     * @param snapshot          fotografia das filas
     * @param collectedAtNanos  instante de {@link System#nanoTime()} em que ela foi publicada
     */
    private record CollectedSnapshot(
            QueueSnapshot snapshot,
            long collectedAtNanos) {
    }

    /** Último snapshot publicado; a troca é atômica e pode ocorrer durante a leitura dos gauges. */
    private final AtomicReference<@Nullable CollectedSnapshot> latest = new AtomicReference<>();

    /** Ciclos de coleta encerrados por falha. */
    private final Counter collectionErrors;
    /** Snapshots publicados com sucesso. */
    private final Counter collectionCompleted;
    /** Duração de cada ciclo de coleta, inclusive os que falharam. */
    private final Timer collectionDuration;

    /**
     * Cria os medidores e registra todos os gauges das filas.
     *
     * @param registry registro Micrometer do contexto
     */
    public QueueMetrics(MeterRegistry registry) {

        collectionErrors = Counter.builder(
                "tracking.queue.collection.errors")
                .register(registry);

        collectionCompleted = Counter.builder(
                "tracking.queue.collection.completed")
                .register(registry);

        collectionDuration = Timer.builder(
                "tracking.queue.collection.duration")
                .publishPercentileHistogram()
                .register(registry);

        registerGauge(
                registry,
                "tracking.queue.packets.pending",
                QueueSnapshot::pendingPackets,
                null);

        registerGauge(
                registry,
                "tracking.queue.packets.eligible",
                QueueSnapshot::eligiblePackets,
                null);

        registerGauge(
                registry,
                "tracking.queue.packets.retry.waiting",
                QueueSnapshot::retryWaitingPackets,
                null);

        registerGauge(
                registry,
                "tracking.queue.packets.oldest.age",
                QueueSnapshot::oldestPacketAgeSeconds,
                "seconds");

        registerGauge(
                registry,
                "tracking.queue.outbox.pending",
                QueueSnapshot::pendingOutboxEvents,
                null);

        registerGauge(
                registry,
                "tracking.queue.outbox.oldest.age",
                QueueSnapshot::oldestOutboxAgeSeconds,
                "seconds");

        // A lista fixa de estados evita séries novas a cada mudança de configuração do banco.
        for (String status : new @NonNull String[] {
                "queued",
                "sending",
                "transmitting",
                "sent",
                "responded",
                "timeout",
                "delivery_failed",
                "delivery_unknown",
                "cancelled"
        }) {
            Gauge.<@NonNull QueueMetrics>builder(
                    "tracking.queue.commands",
                    this,
                    metrics -> metrics.readSnapshot(
                            snapshot -> snapshot
                                    .commandsByStatus()
                                    .getOrDefault(status, 0L)))
                    .tag("status", status)
                    .register(registry);
        }

        Gauge.<@NonNull QueueMetrics>builder(
                "tracking.queue.snapshot.age",
                this,
                QueueMetrics::snapshotAgeSeconds)
                .baseUnit("seconds")
                .register(registry);

        Gauge.<@NonNull QueueMetrics>builder(
                "tracking.queue.snapshot.available",
                this,
                metrics -> metrics.latest.get() == null
                        ? 0.0
                        : 1.0)
                .register(registry);
    }

    /**
     * Registra um gauge que lê o último snapshot, opcionalmente com unidade base.
     *
     * @param registry registro Micrometer do contexto
     * @param name     nome do gauge
     * @param reader   função que extrai o valor do snapshot
     * @param baseUnit unidade base, ou nulo quando o valor é adimensional
     */
    private void registerGauge(
            MeterRegistry registry,
            String name,
            ToDoubleFunction<@NonNull QueueSnapshot> reader,
            @Nullable String baseUnit) {

        var builder = Gauge.<@NonNull QueueMetrics>builder(
                name,
                this,
                metrics -> metrics.readSnapshot(reader));

        if (baseUnit != null) {
            builder.baseUnit(baseUnit);
        }

        builder.register(registry);
    }

    /**
     * Lê um valor do snapshot atual.
     *
     * @param reader função que extrai o valor do snapshot
     * @return valor lido ou {@code NaN} enquanto não houver snapshot
     */
    private double readSnapshot(
            ToDoubleFunction<@NonNull QueueSnapshot> reader) {

        CollectedSnapshot current = latest.get();

        if (current == null) {
            return Double.NaN;
        }

        return reader.applyAsDouble(current.snapshot());
    }

    /**
     * Calcula a idade do snapshot em segundos.
     *
     * @return segundos desde a publicação ou {@code NaN} enquanto não houver snapshot
     */
    public double snapshotAgeSeconds() {

        CollectedSnapshot current = latest.get();

        if (current == null) {
            return Double.NaN;
        }

        return (System.nanoTime()
                - current.collectedAtNanos())
                / 1_000_000_000.0;
    }

    /**
     * Substitui o snapshot consultado pelos gauges e contabiliza a coleta concluída.
     *
     * @param snapshot fotografia imutável produzida pelo repositório
     * @throws NullPointerException se o snapshot for nulo
     */
    public void publish(QueueSnapshot snapshot) {

        latest.set(
                new CollectedSnapshot(
                        Objects.requireNonNull(snapshot),
                        System.nanoTime()));

        collectionCompleted.increment();
    }

    /**
     * Contabiliza um ciclo de coleta que falhou.
     */
    public void collectionFailed() {
        collectionErrors.increment();
    }

    /**
     * Registra a duração de um ciclo de coleta, inclusive quando ele falhou.
     *
     * @param elapsedNanos duração do ciclo em nanossegundos
     */
    public void recordCollectionDuration(
            long elapsedNanos) {

        collectionDuration.record(
                elapsedNanos,
                TimeUnit.NANOSECONDS);
    }
}
