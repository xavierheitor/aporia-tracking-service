package software.aporia.tracking.observability.errors;

import io.sentry.SentryEvent;
import io.sentry.protocol.SentryException;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifica a assinatura usada para deduplicar erros e a janela de silêncio do limitador.
 *
 * <p>O limitador protege o serviço externo de error tracking sem interferir nos logs locais:
 * repetições da mesma assinatura não podem abrir várias capturas, mas falhas diferentes
 * precisam continuar visíveis.</p>
 */
class ErrorEventLimiterTest {

        /**
         * A primeira ocorrência de uma assinatura deve ser liberada.
         *
         * <p>Se o primeiro evento fosse suprimido, a falha ficaria invisível até a janela
         * expirar.</p>
         */
        @Test
        void shouldSendFirstOccurrence() {

                var limiter = new ErrorEventLimiter(
                                Duration.ofMinutes(1),
                                100);

                assertTrue(
                                limiter.shouldSend(
                                                eventOf("IllegalStateException")));
        }

        /**
         * A repetição da mesma assinatura dentro da janela deve ser suprimida.
         *
         * <p>O cenário reproduz um laço de falhas idênticas, que de outra forma geraria um
         * evento por iteração.</p>
         */
        @Test
        void shouldSuppressRepeatedException() {

                var limiter = new ErrorEventLimiter(
                                Duration.ofMinutes(1),
                                100);

                assertTrue(
                                limiter.shouldSend(
                                                eventOf("IllegalStateException")));

                assertFalse(
                                limiter.shouldSend(
                                                eventOf("IllegalStateException")));
        }

        /**
         * Exceções de tipos diferentes devem ter assinaturas diferentes.
         *
         * <p>Uma falha de banco não pode esconder uma falha de estado só porque ambas ocorrem
         * dentro da mesma janela.</p>
         */
        @Test
        void shouldAllowDifferentExceptions() {

                var limiter = new ErrorEventLimiter(
                                Duration.ofMinutes(1),
                                100);

                assertTrue(
                                limiter.shouldSend(
                                                eventOf("IllegalStateException")));

                assertTrue(
                                limiter.shouldSend(
                                                eventOf("SQLException")));
        }

        /**
         * Evento sem exceção não possui assinatura e deve ser rejeitado.
         *
         * <p>Sem esse tipo de dado o limitador não consegue distinguir recorrências.</p>
         */
        @Test
        void shouldRejectEventsWithoutExceptions() {

                var limiter = new ErrorEventLimiter(
                                Duration.ofMinutes(1),
                                100);

                assertFalse(limiter.shouldSend(new SentryEvent()));
        }

        /**
         * Monta um evento Sentry mínimo com uma exceção tipada e o módulo da aplicação.
         *
         * @param type tipo da exceção usado na assinatura
         * @return evento pronto para o limitador
         */
        private SentryEvent eventOf(String type) {

                var exception = new SentryException();

                exception.setType(type);
                // O módulo entra na assinatura; usar o pacote da aplicação simula a origem real.
                exception.setModule("software.aporia.tracking");

                var event = new SentryEvent();

                event.setExceptions(List.of(exception));

                return event;
        }

        /**
         * Cem threads disputam a mesma assinatura e apenas uma deve ser admitida.
         *
         * <p>O teste protege a atomicidade entre a consulta e o registro da assinatura, que não
         * pode ser rompida por capturas concorrentes da mesma falha.</p>
         *
         * @throws Exception se as tarefas não terminarem dentro do prazo
         */
        @Test
        void shouldAdmitOnlyOneConcurrentOccurrence() throws Exception {

                var limiter = new ErrorEventLimiter(
                                Duration.ofMinutes(1),
                                100);

                int concurrency = 100;

                var ready = new CountDownLatch(concurrency);
                var start = new CountDownLatch(1);

                var results = new ArrayList<Future<Boolean>>();

                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {

                        for (int i = 0; i < concurrency; i++) {

                                results.add(executor.submit(() -> {

                                        ready.countDown();

                                        // Todas as tarefas esperam o mesmo start para maximizar a disputa.
                                        if (!start.await(10, TimeUnit.SECONDS)) {
                                                throw new IllegalStateException(
                                                                "Concurrent test start timeout");
                                        }

                                        return limiter.shouldSend(
                                                        eventOf("IllegalStateException"));
                                }));
                        }

                        boolean allReady = ready.await(10, TimeUnit.SECONDS);

                        start.countDown();

                        assertTrue(allReady);

                        int admitted = 0;

                        // Cada tarefa devolve se conseguiu registrar a assinatura.
                        for (var result : results) {

                                if (result.get(10, TimeUnit.SECONDS)) {
                                        admitted++;
                                }
                        }

                        assertEquals(1, admitted);
                }
        }

        /**
         * A mesma assinatura volta a ser admitida depois da janela de expiração.
         *
         * <p>O limitador deve silenciar recorrências, não esconder uma falha que continua
         * ocorrendo indefinidamente.</p>
         *
         * @throws InterruptedException se a espera do cenário for interrompida
         */
        @Test
        void shouldAllowOccurrenceAfterExpiration()
                        throws InterruptedException {

                var limiter = new ErrorEventLimiter(
                                // Janela curta de 250 ms: a espera de 500 ms deixa a expiração folgada.
                                Duration.ofMillis(250),
                                100);

                assertTrue(
                                limiter.shouldSend(
                                                eventOf("IllegalStateException")));

                Thread.sleep(500);

                assertTrue(
                                limiter.shouldSend(
                                                eventOf("IllegalStateException")));
        }

        /**
         * A causa da exceção também distingue assinaturas.
         *
         * <p>O cenário cobre erros envolvidos por uma mesma exceção externa, mas com origens
         * diferentes.</p>
         */
        @Test
        void shouldDistinguishDifferentExceptionCauses() {

                var limiter = new ErrorEventLimiter(
                                Duration.ofMinutes(1),
                                100);

                var first = eventWithCause("SQLException");

                var second = eventWithCause("IOException");

                assertTrue(limiter.shouldSend(first));

                assertTrue(limiter.shouldSend(second));

                assertFalse(
                                limiter.shouldSend(
                                                eventWithCause("SQLException")));
        }

        /**
         * Monta um evento com a causa e a exceção que a envolve.
         *
         * <p>As duas entram na assinatura, permitindo separar falhas com o mesmo invólucro.</p>
         *
         * @param causeType tipo da causa que originou a falha
         * @return evento com a causa e o invólucro correspondente
         */
        private SentryEvent eventWithCause(String causeType) {

                var cause = new SentryException();
                cause.setType(causeType);
                cause.setModule("java");

                var wrapper = new SentryException();
                wrapper.setType("IllegalStateException");
                wrapper.setModule("software.aporia.tracking");

                var event = new SentryEvent();

                // A lista traz a causa e a exceção que a envolve; ambas contribuem para a assinatura.
                event.setExceptions(List.of(cause, wrapper));

                return event;
        }

}