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

@Component
@Profile("gateway")
@NullMarked
public final class CommandMetrics {

    private final Map<@NonNull DispatchResult, Counter> dispatchResults = new EnumMap<>(DispatchResult.class);

    private final Map<@NonNull DispatchResult, Timer> dispatchDurations = new EnumMap<>(DispatchResult.class);

    private final Counter dispatchErrors;
    private final Counter writeErrors;
    private final Counter recoveryCompleted;
    private final Counter recoveryErrors;

    private final Timer writeDuration;
    private final Timer recoveryDuration;

    public CommandMetrics(MeterRegistry registry) {

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

    public void dispatchCompleted(
            DispatchResult result,
            long elapsedNanos) {

        dispatchResults.get(result).increment();

        dispatchDurations.get(result)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void dispatchFailed() {
        dispatchErrors.increment();
    }

    public void tcpWriteFailed() {
        writeErrors.increment();
    }

    public void recordTcpWriteDuration(long elapsedNanos) {
        writeDuration.record(
                elapsedNanos,
                TimeUnit.NANOSECONDS);
    }

    public void recoveryCompleted() {
        recoveryCompleted.increment();
    }

    public void recoveryFailed() {
        recoveryErrors.increment();
    }

    public void recordRecoveryDuration(long elapsedNanos) {
        recoveryDuration.record(
                elapsedNanos,
                TimeUnit.NANOSECONDS);
    }
}
