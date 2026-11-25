package software.aporia.tracking.observability.errors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import io.sentry.SentryOptions;
import io.sentry.protocol.SentryException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ErrorTrackingConfiguration {

    @Bean
    public ErrorEventLimiter errorEventLimiter() {

        return new ErrorEventLimiter(
                Duration.ofMinutes(1),
                2048);
    }

    @Bean
    public SentryOptions.BeforeSendCallback sanitizeErrorEvents(
            ErrorEventLimiter limiter,
            MeterRegistry registry) {

        Counter admitted = Counter.builder(
                "tracking.error.events.admitted")
                .description("Error events admitted for transmission")
                .register(registry);

        Counter suppressed = Counter.builder(
                "tracking.error.events.suppressed")
                .description("Recurring error events suppressed")
                .register(registry);

        Counter discarded = Counter.builder(
                "tracking.error.events.discarded")
                .description("Error events discarded by capture policy")
                .register(registry);

        return (event, hint) -> {

            var exceptions = event.getExceptions();

            if (exceptions == null || exceptions.isEmpty()) {

                discarded.increment();

                return null;
            }

            event.setUser(null);
            event.setRequest(null);
            event.setExtras(null);
            event.setBreadcrumbs(null);
            event.setMessage(null);
            event.setServerName(null);
            event.setTransaction(null);
            event.setThreads(null);
            event.setDebugMeta(null);
            event.setFingerprints(null);
            event.setUnknown(null);

            // Remove contextos adicionais que podem conter dados sensíveis.
            var contexts = event.getContexts();

            var contextKeys = java.util.Collections.list(
                    contexts.keys());

            for (String key : contextKeys) {

                if (!"runtime".equals(key)
                        && !"spring".equals(key)) {

                    contexts.remove(key);
                }
            }

            for (SentryException exception : exceptions) {

                exception.setValue(null);

                var stacktrace = exception.getStacktrace();

                if (stacktrace != null
                        && stacktrace.getFrames() != null) {

                    for (var frame : stacktrace.getFrames()) {
                        frame.setVars(null);
                        frame.setAbsPath(null);
                        frame.setPreContext(null);
                        frame.setPostContext(null);
                        frame.setContextLine(null);
                        frame.setLock(null);
                        frame.setUnknown(null);
                    }
                }
            }

            event.setThrowable(null);

            if (!limiter.shouldSend(event)) {

                suppressed.increment();

                return null;
            }

            admitted.increment();

            return event;
        };
    }
}