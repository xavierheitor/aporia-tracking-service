package software.aporia.tracking.observability.metrics;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import software.aporia.tracking.command.dispatcher.CommandDispatcher.DispatchResult;

/**
 * Registra os desfechos do dispatch e da recuperação de comandos no perfil {@code gateway}.
 *
 * <p>Os contadores e timers usam o enum {@link DispatchResult} como única tag variável ({@code result}),
 * portanto a cardinalidade é limitada e não inclui tenant, dispositivo ou comando. As durações são medidas
 * pelo dispatcher com {@link System#nanoTime()} e registradas depois que a operação retorna. O resultado
 * {@code SENT} significa apenas escrita local concluída, não confirmação do rastreador.</p>
 *
 * <p>A classe é um bean singleton; contadores e timers do Micrometer são thread-safe.</p>
 */
@Component
@Profile("gateway")
@NullMarked
public final class CommandMetrics {

    /** Contador de desfechos, indexado pelo resultado do dispatch. */
    private final Map<@NonNull DispatchResult, Counter> dispatchResults = new EnumMap<>(DispatchResult.class);

    /** Duração de cada desfecho, indexada pelo resultado do dispatch. */
    private final Map<@NonNull DispatchResult, Timer> dispatchDurations = new EnumMap<>(DispatchResult.class);

    /** Falhas inesperadas que impediram a produção de um resultado. */
    private final Counter dispatchErrors;

    /** Falhas de escrita no socket da sessão. */
    private final Counter writeErrors;

    /** Ciclos de recuperação concluídos sem exceção. */
    private final Counter recoveryCompleted;

    /** Ciclos de recuperação encerrados por exceção. */
    private final Counter recoveryErrors;

    /** Duração da escrita, incluindo tentativas que falharam. */
    private final Timer writeDuration;

    /** Duração do ciclo de recuperação, incluindo ciclos que falharam. */
    private final Timer recoveryDuration;

    /**
     * Cria os contadores e timers do dispatcher.
     *
     * @param registry registro Micrometer do contexto
     */
    public CommandMetrics(MeterRegistry registry) {

        // Um contador e um timer por desfecho mantêm a tag limitada ao enum.
        for (DispatchResult result : DispatchResult.values()) {

            String label = Objects.requireNonNull(result.name()
                    .toLowerCase(Locale.ROOT));

            dispatchResults.put(
                    result,
                    Counter.builder("tracking.command.dispatch")
                            .description("Command dispatch results")
                            .tag("result", label)
                            .register(registry));

            dispatchDurations.put(
                    result,
                    Timer.builder("tracking.command.dispatch.duration")
                            .description("Command dispatch duration")
                            .tag("result", label)
                            .publishPercentileHistogram()
                            .register(registry));
        }

        dispatchErrors = Counter.builder(
                "tracking.command.dispatch.errors")
                .description("Unexpected command dispatch failures")
                .register(registry);

        writeErrors = Counter.builder(
                "tracking.command.tcp.write.errors")
                .description("Command TCP write failures")
                .register(registry);

        recoveryCompleted = Counter.builder(
                "tracking.command.recovery")
                .description("Completed command recovery cycles")
                .register(registry);

        recoveryErrors = Counter.builder(
                "tracking.command.recovery.errors")
                .description("Failed command recovery cycles")
                .register(registry);

        writeDuration = Timer.builder(
                "tracking.command.tcp.write")
                .description("Command TCP write duration")
                .publishPercentileHistogram()
                .register(registry);

        recoveryDuration = Timer.builder(
                "tracking.command.recovery.duration")
                .description("Command recovery cycle duration")
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * Registra o desfecho e a duração de uma tentativa de dispatch.
     *
     * @param result       desfecho observado pelo dispatcher
     * @param elapsedNanos duração da operação em nanossegundos
     */
    public void dispatchCompleted(
            DispatchResult result,
            long elapsedNanos) {

        dispatchResults.get(result).increment();

        dispatchDurations.get(result)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Registra uma falha inesperada que não produziu desfecho.
     */
    public void dispatchFailed() {
        dispatchErrors.increment();
    }

    /**
     * Registra uma falha de escrita no socket do dispositivo.
     */
    public void tcpWriteFailed() {
        writeErrors.increment();
    }

    /**
     * Registra a duração de uma escrita no socket, inclusive quando ela falhou.
     *
     * @param elapsedNanos duração da escrita em nanossegundos
     */
    public void recordTcpWriteDuration(long elapsedNanos) {
        writeDuration.record(
                elapsedNanos,
                TimeUnit.NANOSECONDS);
    }

    /**
     * Registra a conclusão de um ciclo de recuperação sem exceção.
     */
    public void recoveryCompleted() {
        recoveryCompleted.increment();
    }

    /**
     * Registra um ciclo de recuperação encerrado por falha.
     */
    public void recoveryFailed() {
        recoveryErrors.increment();
    }

    /**
     * Registra a duração de um ciclo de recuperação, inclusive quando ele falhou.
     *
     * @param elapsedNanos duração do ciclo em nanossegundos
     */
    public void recordRecoveryDuration(long elapsedNanos) {
        recoveryDuration.record(
                elapsedNanos,
                TimeUnit.NANOSECONDS);
    }
}
