package software.aporia.tracking.protocol;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.core.Telemetry;

class SuntechDecoderTest {

    private final SuntechDecoder decoder
            = new SuntechDecoder();

    private byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void shouldDecodeAliveMessage() {

        Telemetry telemetry = decoder.decode(
                ascii("ALV;1234567890\r")
        );

        assertEquals(
                "1234567890",
                telemetry.deviceIdentity()
        );

        assertNull(telemetry.latitude());
        assertNull(telemetry.longitude());

        assertFalse(telemetry.hasPosition());
    }

    @Test
    void shouldRejectAliveWithExtraFields() {

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decode(
                        ascii("ALV;1234567890;EXTRA\r")
                )
        );

        assertEquals(
                "INVALID_FIELD_COUNT",
                exception.getMessage()
        );
    }

    @Test
    void shouldDecodePosition() {
        var telemetry = decoder.decode(
                ascii(
                        "STT;1234567890;013831;20261119;13:30:00;"
                        + "-16.6700;-49.2500;45.5;1\r"
                )
        );

        assertEquals("1234567890", telemetry.deviceIdentity());
        assertEquals(-16.6700, telemetry.latitude());
        assertEquals(-49.2500, telemetry.longitude());
        assertEquals(45.5, telemetry.speedKph());
        assertTrue(telemetry.hasPosition());
    }

    @Test
    void shouldRejectInvalidLatitude() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decode(
                        ascii(
                                "STT;1234567890;013831;20261119;13:30:00;"
                                + "-100.0;-49.2500;45.5;1\r"
                        )
                )
        );

        assertEquals("INVALID_NUMBER", exception.getMessage());
    }

    @Test
    void shouldRejectMissingInlineMap() {
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decode(
                        ascii("STT;1234567890\r")
                )
        );

        assertEquals("INLINE_MAP_REQUIRED", error.getMessage());
    }

    @Test
    void shouldRejectUnsupportedMessage() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decode(
                        ascii("UNKNOWN;1234567890\r")
                )
        );

        assertTrue(exception != null);
    }

    @Test
    void shouldDecodeTelemetryInsideCommandResponse() {

        SuntechDecoder decoder = new SuntechDecoder();

        // Reutilize aqui um frame STT válido dos testes existentes.
        byte[] original = ascii(
                        "STT;1234567890;013831;20261119;13:30:00;"
                        + "-16.6700;-49.2500;45.5;1\r"
                );

        String stt = new String(
                original,
                StandardCharsets.US_ASCII
        );

        byte[] response = ("RES;" + stt).getBytes(StandardCharsets.US_ASCII);

        Telemetry expected = decoder.decode(original);
        Telemetry actual = decoder.decode(response);

        assertEquals(expected, actual);
    }

}
