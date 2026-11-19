package software.aporia.tracking.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class SuntechProtocolTest {

    private byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Test that the SuntechProtocol correctly receives a complete frame and
     * returns it as a single frame in the list. The frame is expected to be
     * delimited by a carriage return character. The test verifies that the
     * received frame matches the expected frame.
     */
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

    /**
     * Test that the SuntechProtocol correctly handles a fragmented frame. The
     * test simulates receiving a frame in two parts, where the first part does
     * not contain the delimiter. The protocol should buffer the first part and
     * wait for the second part to complete
     */
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

    /**
     * Test that the SuntechProtocol correctly handles multiple frames in a
     * single batch of incoming bytes. The test verifies that each frame is
     * correctly extracted and returned as a separate element in the list.
     */
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

    /**
     * Test that the SuntechProtocol correctly extracts the identity from a
     * valid frame. The test verifies that the identity is correctly parsed and
     * returned as a string. The expected identity is "1234567890" for the first
     * frame.
     */
    @Test
    void shouldExtractIdentity() {

        var protocol = new SuntechProtocol();

        String identity = protocol.identify(
                ascii("ALV;1234567890\r")
        );

        assertEquals("1234567890", identity);
    }

    /**
     * Test that the SuntechProtocol throws an IllegalArgumentException when
     * attempting to identify a frame with an invalid identity. The test
     * verifies that the exception is thrown and that the message indicates an
     * invalid identity. The expected exception is thrown when the identity does
     * not conform to the expected format.
     */
    @Test
    void shouldRejectInvalidIdentity() {

        var protocol = new SuntechProtocol();

        // Test that the SuntechProtocol throws an IllegalArgumentException when
        // attempting to identify a frame with an invalid identity.
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

}
