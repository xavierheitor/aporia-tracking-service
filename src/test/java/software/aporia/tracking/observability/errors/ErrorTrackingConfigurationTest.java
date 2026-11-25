package software.aporia.tracking.observability.errors;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SentryStackTrace;

import java.util.Map;

class ErrorTrackingConfigurationTest {

        private SimpleMeterRegistry registry;

        private SentryOptions.BeforeSendCallback callback;

        @BeforeEach
        void setUp() {

                registry = new SimpleMeterRegistry();

                var configuration = new ErrorTrackingConfiguration();

                callback = configuration.sanitizeErrorEvents(
                                configuration.errorEventLimiter(),
                                registry);
        }

        @AfterEach
        void tearDown() {
                registry.close();
        }

        @Test
        void shouldRemoveSensitiveInformation() {

                var event = createExceptionEvent();

                var user = new User();
                user.setEmail("private@example.com");

                var request = new Request();
                request.setUrl("https://example.com/private");

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

        @Test
        void shouldDiscardEventsWithoutExceptions() {

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

        @Test
        void shouldCountSuppressedEvents() {

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

        private double counterValue(String name) {

                return registry.get(name)
                                .counter()
                                .count();
        }

        private SentryEvent createExceptionEvent() {

                var exception = new SentryException();

                exception.setType("IllegalStateException");
                exception.setModule("software.aporia.tracking");

                var event = new SentryEvent();

                event.setExceptions(List.of(exception));

                return event;
        }

        @Test
        void shouldRemoveAdditionalSensitiveMetadata() {

                var event = createExceptionEvent();

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