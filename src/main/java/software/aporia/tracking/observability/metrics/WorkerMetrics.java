package software.aporia.tracking.observability.metrics;

import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.NullMarked;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Instrumenta os desfechos confirmados do processamento e as falhas operacionais do worker.
 *
 * <p>Os contadores de pacote são incrementados por {@link software.aporia.tracking.worker.TrackingWorker}
 * somente depois que {@code processOne()} retorna do repositório, ou seja, após o commit. {@code cycleFailed}
 * cobre exceções que desfazem a transação; {@code cycleEmpty} cobre consultas sem pacote elegível. O timer de
 * processamento mede apenas ciclos com pacote. Não há tags variáveis (tenant, dispositivo ou pacote), então a
 * cardinalidade é fixa.</p>
 *
 * <p>O bean é singleton no perfil {@code worker}; os medidores do Micrometer são thread-safe.</p>
 */
@Component
@Profile("worker")
@NullMarked
public final class WorkerMetrics {

    /** Pacotes concluídos e confirmados. */
    private final Counter processed;
    /** Pacotes rejeitados em definitivo e confirmados. */
    private final Counter rejected;
    /** Pacotes com nova tentativa persistida e confirmada. */
    private final Counter retryScheduled;
    /** Ciclos encerrados por exceção, com a transação desfeita. */
    private final Counter failedCycles;
    /** Ciclos sem pacote elegível. */
    private final Counter emptyCycles;

    /** Duração dos ciclos que trataram um pacote e concluíram o commit. */
    private final Timer processingDuration;

    /**
     * Cria os contadores e o timer do worker.
     *
     * @param registry registro Micrometer do contexto
     */
    public WorkerMetrics(MeterRegistry registry) {

        processed = Counter.builder(
                "tracking.packets.processed")
                .description("Successfully processed packets")
                .register(registry);

        rejected = Counter.builder(
                "tracking.packets.rejected")
                .description("Permanently rejected packets")
                .register(registry);

        retryScheduled = Counter.builder(
                "tracking.packets.retry.scheduled")
                .description("Persisted packet retry schedules")
                .register(registry);

        failedCycles = Counter.builder(
                "tracking.worker.cycles.failed")
                .description("Worker cycles that failed")
                .register(registry);

        emptyCycles = Counter.builder(
                "tracking.worker.cycles.empty")
                .description("Worker cycles without eligible packets")
                .register(registry);

        processingDuration = Timer.builder(
                "tracking.packet.processing")
                .description("Committed packet processing duration")
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * Registra um pacote processado com sucesso após o commit.
     */
    public void packetProcessed() {
        processed.increment();
    }

    /**
     * Registra uma rejeição permanente após o commit.
     */
    public void packetRejected() {
        rejected.increment();
    }

    /**
     * Registra o agendamento persistido de uma nova tentativa.
     */
    public void retryScheduled() {
        retryScheduled.increment();
    }

    /**
     * Registra um ciclo que falhou e teve a transação desfeita.
     */
    public void cycleFailed() {
        failedCycles.increment();
    }

    /**
     * Registra um ciclo que não encontrou pacote elegível.
     */
    public void cycleEmpty() {
        emptyCycles.increment();
    }

    /**
     * Registra a duração de um ciclo que tratou um pacote.
     *
     * @param elapsedNanos duração do ciclo em nanossegundos
     */
    public void recordProcessingDuration(long elapsedNanos) {
        processingDuration.record(
                elapsedNanos,
                TimeUnit.NANOSECONDS);
    }
}
