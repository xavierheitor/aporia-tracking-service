package software.aporia.tracking.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class SuntechMessageClassifierTest {

    private final SuntechMessageClassifier classifier
            = new SuntechMessageClassifier();

    @Test
    void shouldClassifyTelemetry() {
        assertEquals(
                ProtocolMessageKind.TELEMETRY,
                classifier.classify("STT")
        );

        assertEquals(
                ProtocolMessageKind.TELEMETRY,
                classifier.classify("ASTT")
        );
    }

    @Test
    void shouldClassifyCommandResponses() {
        assertEquals(
                ProtocolMessageKind.COMMAND_RESPONSE,
                classifier.classify("RES")
        );

        assertEquals(
                ProtocolMessageKind.COMMAND_RESPONSE,
                classifier.classify("RPR")
        );
    }

    @Test
    void shouldClassifyHeartbeat() {
        assertEquals(
                ProtocolMessageKind.HEARTBEAT,
                classifier.classify("ALV")
        );
    }

    @Test
    void shouldClassifyUnknownMessages() {
        assertEquals(
                ProtocolMessageKind.UNKNOWN,
                classifier.classify("UNKNOWN")
        );
    }
}
