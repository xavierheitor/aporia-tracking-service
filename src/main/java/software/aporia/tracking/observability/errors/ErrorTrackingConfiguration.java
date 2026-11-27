package software.aporia.tracking.observability.errors;

import java.time.Duration;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.sentry.SentryOptions;
import io.sentry.protocol.SentryException;

/**
 * Configura a sanitização e a deduplicação dos eventos enviados ao Sentry.
 *
 * <p>O callback {@link SentryOptions.BeforeSendCallback} remove dados pessoais e payloads antes de o evento
 * sair do processo, aplica o limite de recorrência e contabiliza, em métricas Micrometer, os eventos
 * admitidos, suprimidos e descartados. Os beans existem em qualquer perfil; o envio efetivo depende de {@code
 * sentry.dsn} configurado.</p>
 *
 * <p>O callback pode ser executado concorrentemente pela thread que captura a exceção. Os contadores e o
 * limitador são thread-safe, e a sanitização ocorre antes do cálculo da assinatura para que apenas módulo,
 * tipo e quadros permaneçam disponíveis à deduplicação.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ErrorTrackingConfiguration {

    /**
     * Cria o limitador de recorrência com janela de um minuto e até 2048 assinaturas.
     *
     * @return limitador compartilhado pelo callback de envio
     */
    @Bean
    public @NonNull ErrorEventLimiter errorEventLimiter() {

        return new ErrorEventLimiter(
                Duration.ofMinutes(1),
                2048);
    }

    /**
     * Cria o callback que limpa, conta e limita cada evento antes do envio.
     *
     * <p>Remove usuário, requisição, extras, breadcrumbs, mensagem, servidor, transação, threads, debug
     * metadata, fingerprints e campos desconhecidos; preserva apenas os contextos {@code runtime} e {@code
     * spring} e limpa variáveis e trechos de código dos quadros. Depois disso, eventos sem exceção são
     * descartados, repetições dentro da janela são suprimidas e o restante é admitido.</p>
     *
     * <p>Os contadores não têm tags e são incrementados uma vez por evento avaliado, na ordem das
     * decisões.</p>
     *
     * @param limiter  limitador de recorrência compartilhado
     * @param registry registro onde os três contadores são criados
     * @return callback executado pelo Sentry antes de transmitir o evento
     */
    @Bean
    @NullMarked
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

        return (event, _) -> {

            var exceptions = event.getExceptions();

            if (exceptions == null || exceptions.isEmpty()) {

                discarded.increment();

                return null;
            }

            // O evento é limpo antes de qualquer decisão para não vazar dados nem para o cache de
            // assinaturas.
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

            // A deduplicação acontece por último, quando só permanecem os dados usados na assinatura.
            if (!limiter.shouldSend(event)) {

                suppressed.increment();

                return null;
            }

            admitted.increment();

            return event;
        };
    }
}
