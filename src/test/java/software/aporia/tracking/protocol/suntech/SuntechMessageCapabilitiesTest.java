package software.aporia.tracking.protocol.suntech;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** Verifica a identificação das capacidades de mensagens Suntech, inclusive respostas compostas. */
class SuntechMessageCapabilitiesTest {

    private ProtocolMessageCapabilities classify(String message) {
        return SuntechMessageCapabilities.classify(
                message.getBytes(StandardCharsets.US_ASCII)
        );
    }

    @Test
    void shouldClassifySttAsTelemetry() {

        var result = classify("STT;1234567890;...");

        assertTrue(result.hasTelemetry());
        assertFalse(result.hasCommandResponse());
    }

    @Test
    void shouldClassifyAsttAsTelemetry() {

        var result = classify("ASTT;1234567890;...");

        assertTrue(result.hasTelemetry());
    }

    @Test
    void shouldClassifyHeartbeat() {

        var result = classify("ALV;1234567890;...");

        assertTrue(result.hasHeartbeat());
        assertFalse(result.hasTelemetry());
    }

    @Test
    void shouldClassifyCommandResponse() {

        var result = classify(
                "RES;1234567890;03;04;01;STB.201.1.1.11"
        );

        assertTrue(result.hasCommandResponse());
        assertFalse(result.hasTelemetry());
    }

    @Test
    void shouldClassifyResponseWithTelemetry() {

        var result = classify(
                "RES;STT;1234567890;013831;20261120;08:45:00;"
                + "-16.6700;-49.2500;45.5;1"
        );

        assertTrue(result.hasCommandResponse());
        assertTrue(result.hasTelemetry());
    }

    @Test
    void shouldClassifyRprAsCommandResponse() {

        var result = classify(
                "RPR;1234567890;OK;10;13#0"
        );

        assertTrue(result.hasCommandResponse());
        assertFalse(result.hasTelemetry());
    }

    @Test
    void shouldReturnEmptyForUnknownMessage() {

        var result = classify("UNKNOWN;1234567890");

        assertTrue(result.values().isEmpty());
    }

    @Test
    void shouldReturnEmptyForEmptyPayload() {

        var result = SuntechMessageCapabilities.classify(
                new byte[0]
        );

        assertTrue(result.values().isEmpty());
    }
}
