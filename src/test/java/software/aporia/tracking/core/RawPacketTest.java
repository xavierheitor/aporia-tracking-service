package software.aporia.tracking.core;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/** Verifica a validação do pacote e a proteção dos bytes contra alterações externas. */
class RawPacketTest {

    /**
     * Confere protocolo, identidade e bytes de uma recepção válida.
     */
    @Test
    void shouldCreateValidPacket() {
        RawPacket packet = new RawPacket(
                UUID.randomUUID(),
                "suntech",
                "123456789012345",
                new byte[]{0x01, 0x02, 0x03},
                Instant.now()
        );

        assertEquals("suntech", packet.protocol());
        assertEquals("123456789012345", packet.deviceIdentity());
        assertEquals(3, packet.payload().length);
    }

    /**
     * Exige ao menos um byte para criar um pacote bruto.
     */
    @Test
    void shouldRejectEmptyPayload() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new RawPacket(
                        UUID.randomUUID(),
                        "suntech",
                        "123456789012345",
                        new byte[0],
                        Instant.now()
                )
        );

        assertEquals("Invalid payload size", exception.getMessage());
    }

    /**
     * Altera separadamente o array de entrada e a cópia retornada para verificar a imutabilidade
     * do pacote.
     */
    @Test
    void shouldProtectPayloadFromModification() {
        byte[] bytes = {0x01, 0x02};

        RawPacket packet = new RawPacket(
                UUID.randomUUID(),
                "suntech",
                "123456789012345",
                bytes,
                Instant.now()
        );

        bytes[0] = 0x05;

        assertEquals(0x01, packet.payload()[0]);

        byte[] returned = packet.payload();
        returned[0] = 0x06;

        assertEquals(0x06, returned[0]);
        assertEquals(0x01, packet.payload()[0]);
    }

    /**
     * Confere que o quadro conserva a versão escolhida para decodificação posterior.
     */
    @Test
    void shouldPreserveDecoderVersion() {

        ReceivedFrame frame = new ReceivedFrame(
                "suntech",
                "1234567890",
                "ALV;1234567890\r"
                        .getBytes(StandardCharsets.US_ASCII),
                "suntech-ascii-v3"
        );

        assertEquals(
                "suntech-ascii-v3",
                frame.decoderVersion()
        );
    }
}
