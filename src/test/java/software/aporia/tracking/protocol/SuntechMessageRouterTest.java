package software.aporia.tracking.protocol;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class SuntechMessageRouterTest {

    private final SuntechMessageRouter router
            = new SuntechMessageRouter();

    private MessageProcessingPlan route(String message) {
        return router.route(
                message.getBytes(StandardCharsets.US_ASCII)
        );
    }

    @Test
    void shouldRouteTelemetry() {

        var plan = route("STT;1234567890;...");

        assertTrue(plan.processTelemetry());
        assertFalse(plan.processCommandResponse());
    }

    @Test
    void shouldRouteCommandResponse() {

        var plan = route(
                "RES;1234567890;03;04;01;STB.201.1.1.11"
        );

        assertFalse(plan.processTelemetry());
        assertTrue(plan.processCommandResponse());
    }

    @Test
    void shouldRouteTelemetryAndCommandResponse() {

        var plan = route(
                "RES;STT;1234567890;..."
        );

        assertTrue(plan.processTelemetry());
        assertTrue(plan.processCommandResponse());
    }

    @Test
    void shouldRouteHeartbeat() {

        var plan = route("ALV;1234567890");

        assertTrue(plan.processHeartbeat());
        assertFalse(plan.processTelemetry());
    }

    @Test
    void shouldRejectUnknownMessage() {

        assertThrows(
                IllegalArgumentException.class,
                () -> route("UNKNOWN;1234567890")
        );
    }
}
