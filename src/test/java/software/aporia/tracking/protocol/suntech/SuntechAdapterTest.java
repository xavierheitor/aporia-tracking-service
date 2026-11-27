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

/**
 * Verifica os metadados do adaptador, a adaptação de quadros Suntech completos, fragmentados e
 * agrupados na mesma leitura, e o isolamento entre sessões.
 *
 * <p>O buffer é por conexão: misturar fragmentos de sockets diferentes corromperia a identidade
 * e o conteúdo do quadro.</p>
 */
class SuntechAdapterTest {

    /** Adaptador Suntech exercitado nas leituras simuladas. */
    private final SuntechAdapter adapter
            = new SuntechAdapter();

    /**
     * Confere os identificadores usados para registrar o adaptador e o decodificador.
     *
     * <p>O protocolo vem do cadastro e a versão é gravada com o pacote; os dois valores precisam
     * casar com os registros correspondentes.</p>
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
     *
     * <p>Um quadro terminado por CR deve sair com identidade, tipo e bytes originais.</p>
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

        // O payload preserva os bytes originais, inclusive o CR que delimita a mensagem.
        assertArrayEquals(incoming, frame.payload());

        // Este adaptador não negocia ACK para as mensagens Suntech atuais.
        assertFalse(frame.acknowledgmentRequired());
    }

    /**
     * Divide o quadro entre leituras para exercitar a remontagem na sessão.
     *
     * <p>Um read parcial não pode emitir quadro; o próximo completa o CR e libera apenas um.</p>
     */
    @Test
    void shouldReassembleFragmentedFrame() {

        ProtocolSession session = adapter.newSession();

        byte[] first = "ALV;12345"
                .getBytes(StandardCharsets.US_ASCII);

        byte[] second = "67890\r"
                .getBytes(StandardCharsets.US_ASCII);

        // A primeira leitura termina no meio da identidade e não pode emitir quadro.
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
     *
     * <p>Dois CRs no mesmo read devem produzir dois quadros distintos e na ordem de chegada.</p>
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
     *
     * <p>O fragmento guardado na primeira sessão não pode completar a mensagem da segunda.</p>
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

        // Só depois de receber o próprio CR a primeira sessão emite seu quadro.
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
