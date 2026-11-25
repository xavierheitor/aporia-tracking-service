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

class ErrorEventLimiterTest {

        @Test
        void shouldSendFirstOccurrence() {

                var limiter = new ErrorEventLimiter(
                                Duration.ofMinutes(1),
                                100);

                assertTrue(
                                limiter.shouldSend(
                                                eventOf("IllegalStateException")));
        }

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

        @Test
        void shouldRejectEventsWithoutExceptions() {

                var limiter = new ErrorEventLimiter(
                                Duration.ofMinutes(1),
                                100);

                assertFalse(limiter.shouldSend(new SentryEvent()));
        }

        private SentryEvent eventOf(String type) {

                var exception = new SentryException();

                exception.setType(type);
                exception.setModule("software.aporia.tracking");

                var event = new SentryEvent();

                event.setExceptions(List.of(exception));

                return event;
        }

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

                        for (var result : results) {

                                if (result.get(10, TimeUnit.SECONDS)) {
                                        admitted++;
                                }
                        }

                        assertEquals(1, admitted);
                }
        }

        @Test
        void shouldAllowOccurrenceAfterExpiration()
                        throws InterruptedException {

                var limiter = new ErrorEventLimiter(
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

        private SentryEvent eventWithCause(String causeType) {

                var cause = new SentryException();
                cause.setType(causeType);
                cause.setModule("java");

                var wrapper = new SentryException();
                wrapper.setType("IllegalStateException");
                wrapper.setModule("software.aporia.tracking");

                var event = new SentryEvent();

                event.setExceptions(List.of(cause, wrapper));

                return event;
        }

}