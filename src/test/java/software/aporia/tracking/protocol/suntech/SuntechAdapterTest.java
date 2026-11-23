package software.aporia.tracking.protocol.suntech;

import software.aporia.tracking.protocol.ProtocolFrame;
import software.aporia.tracking.protocol.ProtocolSession;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** Verifica a adaptação de quadros Suntech completos, fragmentados e agrupados na mesma leitura. */
class SuntechAdapterTest {

    /** Adaptador Suntech exercitado nas leituras simuladas. */
    private final SuntechAdapter adapter
            = new SuntechAdapter();

    /**
     * Confere os identificadores usados para registrar o adaptador e o decodificador.
     */
    @Test
    void shouldExposeProtocolMetadata() {

        assertEquals("suntech", adapter.protocolId());

        assertEquals(
                "suntech-ascii-v3",
                adapter.decoderVersion()
        );
    }

    /**
     * Confere os metadados produzidos a partir de uma mensagem completa.
     */
    @Test
    void shouldConvertReceivedFrame() {

        ProtocolSession session = adapter.newSession();

        byte[] incoming = ("ALV;1234567890\r").getBytes(StandardCharsets.US_ASCII);

        List<ProtocolFrame> frames
                = session.receive(incoming);

        assertEquals(1, frames.size());

        ProtocolFrame frame = frames.get(0);

        assertEquals(
                "1234567890",
                frame.deviceIdentity()
        );

        assertEquals("ALV", frame.messageType());

        assertArrayEquals(incoming, frame.payload());

        assertFalse(frame.acknowledgmentRequired());
    }

    /**
     * Divide o quadro entre leituras para exercitar a remontagem na sessão.
     */
    @Test
    void shouldReassembleFragmentedFrame() {

        ProtocolSession session = adapter.newSession();

        byte[] first = "ALV;12345"
                .getBytes(StandardCharsets.US_ASCII);

        byte[] second = "67890\r"
                .getBytes(StandardCharsets.US_ASCII);

        assertTrue(session.receive(first).isEmpty());

        List<ProtocolFrame> frames
                = session.receive(second);

        assertEquals(1, frames.size());

        assertEquals(
                "1234567890",
                frames.get(0).deviceIdentity()
        );
    }

    /**
     * Exercita a emissão de vários quadros recebidos em uma leitura.
     */
    @Test
    void shouldHandleMultipleFrames() {

        ProtocolSession session = adapter.newSession();

        byte[] incoming = ("ALV;1234567890\r"
                + "ALV;0987654321\r").getBytes(StandardCharsets.US_ASCII);

        List<ProtocolFrame> frames
                = session.receive(incoming);

        assertEquals(2, frames.size());

        assertEquals(
                "1234567890",
                frames.get(0).deviceIdentity()
        );

        assertEquals(
                "0987654321",
                frames.get(1).deviceIdentity()
        );
    }

    /**
     * Verifica que os buffers de duas conexões evoluem independentemente.
     */
    @Test
    void shouldIsolateSessions() {

        ProtocolSession firstSession
                = adapter.newSession();

        ProtocolSession secondSession
                = adapter.newSession();

        firstSession.receive(
                "ALV;12345".getBytes(StandardCharsets.US_ASCII)
        );

        List<ProtocolFrame> secondFrames
                = secondSession.receive(
                        "ALV;0987654321\r"
                                .getBytes(StandardCharsets.US_ASCII)
                );

        assertEquals(1, secondFrames.size());

        assertEquals(
                "0987654321",
                secondFrames.get(0).deviceIdentity()
        );

        List<ProtocolFrame> firstFrames
                = firstSession.receive(
                        "67890\r".getBytes(StandardCharsets.US_ASCII)
                );

        assertEquals(1, firstFrames.size());

        assertEquals(
                "1234567890",
                firstFrames.get(0).deviceIdentity()
        );
    }
}
