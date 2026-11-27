package software.aporia.tracking.protocol.suntech;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Reconstitui quadros ASCII delimitados por retorno de carro e localiza a identidade declarada em cada
 * cabeçalho suportado.
 *
 * <p>{@link #receive(byte[])} cuida dos limites das mensagens; {@link #identify(byte[])} valida o envelope
 * textual e a identidade. Os valores de telemetria são interpretados separadamente por {@link
 * SuntechDecoder}.</p>
 *
 * <p>Uma instância por conexão guarda o fragmento entre leituras e não é segura para uso concorrente. O
 * buffer contém apenas os bytes após o último CR. {@link #receive(byte[])} não valida cabeçalho nem
 * identidade: isso só ocorre em {@link #identify(byte[])}. Após uma exceção de limite, o chamador deve
 * encerrar a conexão, pois os bytes rejeitados não são recuperados.</p>
 */
public class SuntechProtocol {

    /** Limite de bytes que o enquadrador aceita para uma mensagem, incluindo o CR; é inclusivo. */
    private static final int MAX_FRAME_SIZE = 8192;
    /** Byte de retorno de carro que encerra uma mensagem Suntech. */
    private static final byte DELIMITER = '\r';

    /** Cabeçalhos aceitos para identificar o dispositivo no quadro. */
    private static final Set<String> HEADERS
            = Set.of("ALV", "STT", "ASTT", "RES", "RPR");

    /** Guarda somente os bytes após o último CR, à espera do terminador da mensagem. */
    private byte[] buffer = new byte[0];

    /**
     * Emite todos os quadros terminados por CR e conserva o fragmento restante para a próxima
     * leitura.
     *
     * <p>O limite de {@value #MAX_FRAME_SIZE} bytes inclui o CR. Uma leitura sem CR que já ocupe o limite é
     * rejeitada antes de completar o quadro, pois o terminador ainda precisaria caber.</p>
     *
     * @param incoming bytes disponíveis nesta leitura TCP, possivelmente um fragmento
     * @return quadros completos nesta leitura, em ordem de recepção
     * @throws IllegalArgumentException se {@code incoming} for nulo ou o quadro exceder o limite
     */
    public List<byte[]> receive(byte[] incoming) {

        if (incoming == null) {
            throw new IllegalArgumentException("NULL_INPUT");
        }

        List<byte[]> frames = new ArrayList<>();
        // start marca o começo do trecho ainda não consumido desta leitura.
        // buffer contém somente o fragmento guardado da leitura anterior.
        int start = 0;

        for (int i = 0; i < incoming.length; i++) {

            if (incoming[i] == DELIMITER) {

                int frameSize = buffer.length + i - start + 1;

                // O CR entra no tamanho; 8192 é aceito e 8193 é recusado.
                if (frameSize > MAX_FRAME_SIZE) {
                    throw new IllegalArgumentException(
                            "FRAME_TOO_LARGE"
                    );
                }

                // Junta o fragmento anterior ao trecho atual, incluindo o CR final.
                byte[] frame = Arrays.copyOf(
                        buffer,
                        frameSize
                );

                System.arraycopy(
                        incoming,
                        start,
                        frame,
                        buffer.length,
                        i - start + 1
                );

                frames.add(frame);

                buffer = new byte[0];
                start = i + 1;

            } else if (buffer.length + i - start + 1
                    >= MAX_FRAME_SIZE) {
                // Sem CR, é preciso reservar um byte para o terminador dentro do limite.

                throw new IllegalArgumentException(
                        "FRAME_TOO_LARGE"
                );
            }
        }

        int remaining = incoming.length - start;

        byte[] remainder = Arrays.copyOf(
                buffer,
                buffer.length + remaining
        );

        System.arraycopy(
                incoming,
                start,
                remainder,
                buffer.length,
                remaining
        );

        // Só o trecho após o último CR precisa sobreviver até a próxima leitura do socket.
        buffer = remainder;

        return frames;
    }

    /**
     * Valida o envelope e extrai a identidade, inclusive quando a resposta contém um cabeçalho de posição
     * embutido.
     *
     * <p>O quadro precisa terminar em CR e conter apenas ASCII imprimível antes dele. Somente o cabeçalho e a
     * identidade são validados aqui; os campos restantes são interpretados pelo decodificador.</p>
     *
     * @param frame quadro recebido para interpretação ou ingestão
     * @return identidade extraída do cabeçalho reconhecido
     * @throws IllegalArgumentException se o quadro, o cabeçalho ou a identidade forem inválidos
     */
    public String identify(byte[] frame) {

        if (frame == null
                || frame.length < 2
                || frame.length > MAX_FRAME_SIZE
                || frame[frame.length - 1] != DELIMITER) {

            throw new IllegalArgumentException("INVALID_FRAME");
        }

        // A faixa imprimível também impede CR embutido no meio do quadro.
        for (int i = 0; i < frame.length - 1; i++) {
            if (frame[i] < 32 || frame[i] > 126) {
                throw new IllegalArgumentException("INVALID_FRAME");
            }
        }

        String message = new String(
                frame,
                0,
                frame.length - 1,
                StandardCharsets.US_ASCII
        );

        // -1 preserva campos vazios finais; descartá-los esconderia campos ausentes.
        String[] parts = message.split(";", -1);

        if (parts.length < 2 || !HEADERS.contains(parts[0])) {
            throw new IllegalArgumentException(
                    "UNSUPPORTED_MESSAGE"
            );
        }

        // Normal: RES;IDENTIDADE;...  Com posição embutida: RES;STT;IDENTIDADE;...
        int identityIndex = 1;

        if ("RES".equals(parts[0])
                && parts.length > 1
                && ("STT".equals(parts[1])
                || "ASTT".equals(parts[1]))) {

            identityIndex = 2;
        }

        if (parts.length <= identityIndex) {
            throw new IllegalArgumentException(
                    "INVALID_IDENTITY"
            );
        }

        String identity = parts[identityIndex];

        // A identidade declarada é apenas o número do equipamento; não autentica a conexão.
        if (!identity.matches("\\d{10}")) {
            throw new IllegalArgumentException(
                    "INVALID_IDENTITY"
            );
        }

        return identity;
    }

}
