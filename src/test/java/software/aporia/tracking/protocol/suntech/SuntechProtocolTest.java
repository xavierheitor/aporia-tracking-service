package software.aporia.tracking.protocol.suntech;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** Exercita os limites de enquadramento TCP e a extração de identidade dos formatos Suntech. */
class SuntechProtocolTest {

    private byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /** Verifica que um quadro terminado por CR é emitido com os bytes originais. */
    @Test
    void shouldReceiveCompleteFrame() {

        var protocol = new SuntechProtocol();

        var frames = protocol.receive(
                ascii("ALV;1234567890\r")
        );

        assertEquals(1, frames.size());

        assertArrayEquals(
                ascii("ALV;1234567890\r"),
                frames.get(0)
        );
    }

    /** Verifica que a sessão guarda o primeiro fragmento até receber o delimitador. */
    @Test
    void shouldHandleFragmentedFrame() {

        var protocol = new SuntechProtocol();

        var first = protocol.receive(
                ascii("ALV;12345")
        );

        assertTrue(first.isEmpty());

        var second = protocol.receive(
                ascii("67890\r")
        );

        assertEquals(1, second.size());

        assertArrayEquals(
                ascii("ALV;1234567890\r"),
                second.get(0)
        );
    }

    /** Verifica que uma única leitura TCP pode produzir vários quadros completos. */
    @Test
    void shouldHandleMultipleFrames() {

        var protocol = new SuntechProtocol();

        var frames = protocol.receive(
                ascii(
                        "ALV;1234567890\r"
                        + "ALV;0987654321\r"
                )
        );

        assertEquals(2, frames.size());
    }

    /** Confere a identidade extraída de um quadro válido. */
    @Test
    void shouldExtractIdentity() {

        var protocol = new SuntechProtocol();

        String identity = protocol.identify(
                ascii("ALV;1234567890\r")
        );

        assertEquals("1234567890", identity);
    }

    /** Exercita a rejeição da identidade fora do formato Suntech. */
    @Test
    void shouldRejectInvalidIdentity() {

        var protocol = new SuntechProtocol();

        // A identidade inválida deve impedir o vínculo do quadro com uma sessão.
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> protocol.identify(
                        ascii("ALV;ABC\r")
                )
        );

        assertEquals(
                "INVALID_IDENTITY",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectUnsupportedHeader() {

        var protocol = new SuntechProtocol();

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> protocol.identify(
                        ascii("UNKNOWN;1234567890\r")
                )
        );

        assertEquals(
                "UNSUPPORTED_MESSAGE",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNonAsciiFrame() {

        var protocol = new SuntechProtocol();

        byte[] invalid = {
            'A', 'L', 'V', ';', 0x01, '\r'
        };

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> protocol.identify(invalid)
        );

        assertEquals(
                "INVALID_FRAME",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectOversizedFrame() {

        var protocol = new SuntechProtocol();

        byte[] oversized = new byte[8193];
        Arrays.fill(oversized, (byte) 'A');

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> protocol.receive(oversized)
        );

        assertEquals(
                "FRAME_TOO_LARGE",
                exception.getMessage()
        );
    }

    @Test
    void shouldAcceptMaximumFrameSize() {

        var protocol = new SuntechProtocol();

        byte[] frame = new byte[8192];
        Arrays.fill(frame, (byte) 'A');
        frame[8191] = '\r';

        var frames = protocol.receive(frame);

        assertEquals(1, frames.size());
        assertEquals(8192, frames.get(0).length);
    }

    @Test
    void shouldIdentifyProgrammingResponse() {

        SuntechProtocol protocol = new SuntechProtocol();

        byte[] frame = ("RPR;1234567890;OK;16;70#60\r").getBytes(StandardCharsets.US_ASCII);

        assertEquals(
                "1234567890",
                protocol.identify(frame)
        );
    }

    @Test
    void shouldIdentifyEmbeddedTelemetryResponse() {

        SuntechProtocol protocol = new SuntechProtocol();

        byte[] frame = ("RES;STT;1234567890;013831;20261120;08:45:00;"
                + "-16.6700;-49.2500;45.5;1\r").getBytes(StandardCharsets.US_ASCII);

        assertEquals(
                "1234567890",
                protocol.identify(frame)
        );
    }

    @Test
    void shouldIdentifyNormalCommandResponse() {

        SuntechProtocol protocol = new SuntechProtocol();

        byte[] frame = ("RES;1234567890;03;04;01;STB.201.1.1.11\r").getBytes(StandardCharsets.US_ASCII);

        assertEquals(
                "1234567890",
                protocol.identify(frame)
        );
    }

    @Test
    void shouldRejectEmbeddedResponseWithoutIdentity() {

        SuntechProtocol protocol = new SuntechProtocol();

        byte[] frame = ("RES;STT;\r").getBytes(StandardCharsets.US_ASCII);

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> protocol.identify(frame)
        );
        assertEquals("INVALID_IDENTITY", error.getMessage());
    }

}
