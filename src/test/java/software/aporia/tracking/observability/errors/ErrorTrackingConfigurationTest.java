package software.aporia.tracking.observability.errors;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SentryStackTrace;
import io.sentry.protocol.User;

/**
 * Verifica a política de captura do Sentry: remoção de dados sensíveis antes do envio, descarte
 * de eventos sem exceção e contagem de eventos admitidos, suprimidos e descartados.
 *
 * <p>Os contadores são a única visibilidade de que a sanitização e o limitador não silenciaram
 * falhas reais.</p>
 */
class ErrorTrackingConfigurationTest {

        /** Registro isolado para conferir eventos admitidos, suprimidos e descartados. */
        private final @NonNull SimpleMeterRegistry registry = new SimpleMeterRegistry();

        /** Callback de sanitização criado no setUp e reutilizado pelos cenários. */
        private SentryOptions.BeforeSendCallback callback;

        /**
         * Cria o callback de sanitização com um registro de métricas isolado por teste.
         */
        @BeforeEach
        void setUp() {

                var configuration = new ErrorTrackingConfiguration();

                callback = configuration.sanitizeErrorEvents(
                                configuration.errorEventLimiter(),
                                registry);
        }

        /**
         * Fecha o registro para não compartilhar medidores entre cenários.
         */
        @AfterEach
        void tearDown() {
                registry.close();
        }

        /**
         * Remove usuário, requisição, extras, servidor e transação, mas preserva o tipo da
         * exceção.
         *
         * <p>O evento saneado deve continuar útil para localizar a falha sem levar dados
         * pessoais ou credenciais ao serviço externo.</p>
         */
        @Test
        void shouldRemoveSensitiveInformation() {

                var event = createExceptionEvent();

                var user = new User();
                user.setEmail("private@example.com");

                var request = new Request();
                request.setUrl("https://example.com/private");

                // Cada campo preenchido representa uma categoria de dado que não pode sair do processo.
                event.setUser(user);
                event.setRequest(request);
                event.setExtra("token", "secret");
                event.setServerName("private-server");
                event.setTransaction("private-transaction");

                event.getExceptions()
                                .getFirst()
                                .setValue("password=secret");

                var result = callback.execute(event, new Hint());

                assertNotNull(result);

                assertNull(result.getUser());
                assertNull(result.getRequest());
                assertNull(result.getExtras());
                assertNull(result.getServerName());
                assertNull(result.getTransaction());

                var exception = result.getExceptions().getFirst();

                assertNull(exception.getValue());

                assertEquals(
                                "IllegalStateException",
                                exception.getType());

                // O contador prova que o evento saneado seguiu para envio.
                assertEquals(
                                1.0,
                                counterValue("tracking.error.events.admitted"));

                assertEquals(
                                0.0,
                                counterValue("tracking.error.events.suppressed"));

                assertEquals(
                                0.0,
                                counterValue("tracking.error.events.discarded"));
        }

        /**
         * Evento sem exceção é descartado e contabilizado.
         *
         * <p>Sem exceção não há assinatura para o limitador nem informação útil de falha.</p>
         */
        @Test
        void shouldDiscardEventsWithoutExceptions() {

                // Um SentryEvent vazio não tem exceção para identificar a falha.
                var result = callback.execute(
                                new SentryEvent(),
                                new Hint());

                assertNull(result);

                assertEquals(
                                1.0,
                                counterValue("tracking.error.events.discarded"));

                assertEquals(
                                0.0,
                                counterValue("tracking.error.events.admitted"));

                assertEquals(
                                0.0,
                                counterValue("tracking.error.events.suppressed"));
        }

        /**
         * A segunda captura idêntica é suprimida e incrementa o contador correspondente.
         *
         * <p>O cenário confirma que a sanitização acontece antes do limitador e que a supressão
         * é contada separadamente do descarte.</p>
         */
        @Test
        void shouldCountSuppressedEvents() {

                // A assinatura usa tipo e módulo, então os dois eventos são equivalentes.
                var first = callback.execute(
                                createExceptionEvent(),
                                new Hint());

                var second = callback.execute(
                                createExceptionEvent(),
                                new Hint());

                assertNotNull(first);
                assertNull(second);

                assertEquals(
                                1.0,
                                counterValue("tracking.error.events.admitted"));

                assertEquals(
                                1.0,
                                counterValue("tracking.error.events.suppressed"));

                assertEquals(
                                0.0,
                                counterValue("tracking.error.events.discarded"));
        }

        /**
         * Consulta o total acumulado do contador registrado pelo callback.
         *
         * @param name nome do medidor consultado
         * @return valor acumulado do contador
         */
        private double counterValue(@NonNull String name) {

                return registry.get(name)
                                .counter()
                                .count();
        }

        /**
         * Cria um evento com uma única exceção do módulo da aplicação.
         *
         * @return evento aceito pela política de captura antes do limite de recorrência
         */
        private SentryEvent createExceptionEvent() {

                var exception = new SentryException();

                exception.setType("IllegalStateException");
                exception.setModule("software.aporia.tracking");

                var event = new SentryEvent();

                event.setExceptions(List.of(exception));

                return event;
        }

        /**
         * Remove contextos, dados desconhecidos, fingerprint e metadados de código, preservando
         * arquivo, função e linha.
         *
         * <p>Esses campos podem carregar segredos ou caminhos locais; a posição no código
         * continua necessária para localizar a falha.</p>
         */
        @Test
        void shouldRemoveAdditionalSensitiveMetadata() {

                var event = createExceptionEvent();

                // O contexto customizado não está na lista de contextos permitidos (runtime e spring).
                event.getContexts().put(
                                "custom",
                                Map.of("token", "secret"));

                event.setUnknown(
                                Map.of("private", "secret"));

                event.setFingerprints(
                                List.of("private-identifier"));

                var frame = new SentryStackFrame();

                frame.setModule("software.aporia.tracking.worker");
                frame.setFunction("processOne");
                frame.setFilename("TrackingWorker.java");
                frame.setLineno(84);

                // Caminho absoluto, contexto de linha e variáveis do frame podem expor código e segredos.
                frame.setAbsPath("/private/source/TrackingWorker.java");
                frame.setContextLine("password=secret");
                frame.setPreContext(List.of("private source"));
                frame.setPostContext(List.of("private source"));
                frame.setVars(Map.of("password", "secret"));
                frame.setUnknown(Map.of("private", "secret"));

                var exception = event.getExceptions().getFirst();

                exception.setStacktrace(
                                new SentryStackTrace(List.of(frame)));

                var result = callback.execute(event, new Hint());

                assertNotNull(result);

                assertNull(result.getContexts().get("custom"));
                assertNull(result.getUnknown());
                assertNull(result.getFingerprints());

                var sanitizedFrame = result.getExceptions()
                                .getFirst()
                                .getStacktrace()
                                .getFrames()
                                .getFirst();

                assertNull(sanitizedFrame.getAbsPath());
                assertNull(sanitizedFrame.getContextLine());
                assertNull(sanitizedFrame.getPreContext());
                assertNull(sanitizedFrame.getPostContext());
                assertNull(sanitizedFrame.getVars());
                assertNull(sanitizedFrame.getUnknown());

                // Arquivo, função e linha continuam úteis para localizar a falha sem revelar o caminho local.
                assertEquals(
                                "TrackingWorker.java",
                                sanitizedFrame.getFilename());

                assertEquals(
                                "processOne",
                                sanitizedFrame.getFunction());

                assertEquals(
                                Integer.valueOf(84),
                                sanitizedFrame.getLineno());
        }
}
