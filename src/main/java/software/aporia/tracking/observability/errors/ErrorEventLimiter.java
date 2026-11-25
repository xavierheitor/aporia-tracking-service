package software.aporia.tracking.observability.errors;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

import org.jspecify.annotations.NonNull;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.sentry.SentryEvent;
import io.sentry.protocol.SentryException;

/**
 * Limita o envio de exceções recorrentes ao serviço externo de error tracking.
 *
 * Não interfere nos logs locais da aplicação.
 */
public final class ErrorEventLimiter {

    private final Cache<String, @NonNull Boolean> recentEvents;

    public ErrorEventLimiter(
            Duration interval,
            long maximumSignatures) {

        this.recentEvents = Caffeine.newBuilder()
                .expireAfterWrite(Objects.requireNonNull(interval))
                .maximumSize(maximumSignatures)
                .build();
    }

    public boolean shouldSend(SentryEvent event) {

        List<SentryException> exceptions = event.getExceptions();

        if (exceptions == null || exceptions.isEmpty()) {
            return false;
        }

        String signature = signatureOf(exceptions);

        return recentEvents.asMap()
                .putIfAbsent(signature, true) == null;
    }

    private String signatureOf(
            List<SentryException> exceptions) {

        StringJoiner signature = new StringJoiner("|");

        for (SentryException exception : exceptions) {

            signature.add(String.valueOf(exception.getModule()));
            signature.add(String.valueOf(exception.getType()));

            var stacktrace = exception.getStacktrace();

            if (stacktrace == null
                    || stacktrace.getFrames() == null) {
                continue;
            }

            var frames = stacktrace.getFrames();

            for (int i = frames.size() - 1; i >= 0; i--) {

                var frame = frames.get(i);
                String module = frame.getModule();

                if (module != null
                        && module.startsWith(
                                "software.aporia.tracking.")) {

                    signature.add(module);
                    signature.add(String.valueOf(frame.getFunction()));
                    signature.add(String.valueOf(frame.getLineno()));

                    break;
                }
            }
        }

        return signature.toString();
    }
}
