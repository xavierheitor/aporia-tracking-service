package software.aporia.tracking.core;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/**
 * Verifica a validação e a imutabilidade do pacote bruto, além da preservação da versão do
 * decodificador no quadro recebido.
 *
 * <p>Os dois records atravessam a fronteira entre a recepção TCP e a persistência: alterações
 * externas nos arrays não podem mudar o conteúdo que será processado depois.</p>
 */
class RawPacketTest {

    /**
     * Confere protocolo, identidade e bytes de uma recepção válida.
     *
     * <p>O construtor precisa aceitar o payload Suntech mínimo e preservar os três campos sem
     * conversão.</p>
     */
    @Test
    void shouldCreateValidPacket() {
        RawPacket packet = new RawPacket(
                UUID.randomUUID(),
                "suntech",
                "123456789012345",
                // Três bytes arbitrários bastam: a validação só exige tamanho entre 1 e 65536.
                new byte[]{0x01, 0x02, 0x03},
                Instant.now()
        );

        assertEquals("suntech", packet.protocol());
        assertEquals("123456789012345", packet.deviceIdentity());
        assertEquals(3, packet.payload().length);
    }

    /**
     * Exige ao menos um byte para criar um pacote bruto.
     *
     * <p>Um pacote vazio nunca pode chegar à fila de processamento; a mensagem fixa permite ao
     * chamador distinguir o tamanho inválido de outros campos ausentes.</p>
     */
    @Test
    void shouldRejectEmptyPayload() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new RawPacket(
                        UUID.randomUUID(),
                        "suntech",
                        "123456789012345",
                        // Zero bytes viola o limite mínimo exigido pelo construtor.
                        new byte[0],
                        Instant.now()
                )
        );

        assertEquals("Invalid payload size", exception.getMessage());
    }

    /**
     * Altera separadamente o array de entrada e a cópia retornada para verificar a imutabilidade
     * do pacote.
     *
     * <p>Nenhuma das duas mutações pode alterar o conteúdo interno: o pacote é a fonte confiável
     * para a persistência e para o worker.</p>
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

        // A primeira mutação atinge o array do chamador; a segunda, a cópia devolvida.
        bytes[0] = 0x05;

        assertEquals(0x01, packet.payload()[0]);

        byte[] returned = packet.payload();
        returned[0] = 0x06;

        assertEquals(0x06, returned[0]);
        assertEquals(0x01, packet.payload()[0]);
    }

    /**
     * Confere que o quadro conserva a versão escolhida para decodificação posterior.
     *
     * <p>O teste usa {@link ReceivedFrame}, não {@link RawPacket}: a versão precisa sobreviver à
     * ingestão para que o worker escolha o decodificador mesmo depois de um reinício.</p>
     */
    @Test
    void shouldPreserveDecoderVersion() {

        ReceivedFrame frame = new ReceivedFrame(
                "suntech",
                "1234567890",
                // Quadro ALV completo em ASCII, com o CR que delimita a mensagem.
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
