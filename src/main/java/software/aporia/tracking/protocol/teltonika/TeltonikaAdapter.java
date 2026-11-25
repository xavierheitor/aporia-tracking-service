package software.aporia.tracking.protocol.teltonika;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;

import software.aporia.tracking.protocol.DeviceDescription;
import software.aporia.tracking.protocol.ProtocolAdapter;
import software.aporia.tracking.protocol.ProtocolFrame;
import software.aporia.tracking.protocol.ProtocolSession;

/**
 * Conduz o login por IMEI e o enquadramento AVL em TCP. Cada conexão mantém um buffer próprio
 * para leituras fragmentadas.
 *
 * <p>O adaptador cria a sessão e descreve o codec para a descoberta. A sessão
 * remonta os bytes e delega a validação do lote a {@link TeltonikaAvl}, também
 * usado pelo worker para extrair as leituras dos bytes persistidos.</p>
 */
public final class TeltonikaAdapter implements ProtocolAdapter {
    /** Valida o lote AVL completo antes de a sessão emitir o quadro. */
    private static final TeltonikaAvl DECODER = new TeltonikaAvl();

    /**
     * Reconhece o byte inicial do comprimento de login TCP.
     *
     * @param first primeiro byte da conexão, interpretado sem sinal
     * @return verdadeiro quando o prefixo é compatível com o adaptador
     */
    @Override public boolean acceptsFirstByte(int first) { return first == 0; }

    /**
     * Identifica o protocolo usado para localizar o cadastro e registrar a descoberta.
     *
     * @return identificador do protocolo desta implementação
     */
    @Override public @NonNull String protocolId() { return "teltonika"; }
    /**
     * Obtém do decodificador a versão que acompanhará o lote bruto.
     *
     * @return versão usada para interpretar os bytes persistidos
     */
    @Override public String decoderVersion() { return DECODER.decoderVersion(); }
    /**
     * Inicia um estado independente para a sequência de login e lotes AVL.
     *
     * @return sessão de protocolo com estado independente
     */
    @Override public ProtocolSession newSession() { return new Session(); }

    /**
     * Identifica o codec de um lote validado para a fila de descoberta.
     *
     * @param frame quadro recebido para interpretação ou ingestão
     * @return metadados que podem compor a pendência de autorização
     */
    @Override
    public DeviceDescription describe(ProtocolFrame frame) {
        // A sessão só emite um quadro AVL após verificar a estrutura do lote e seu CRC.
        if (!"AVL".equals(frame.messageType())) throw new IllegalArgumentException("AVL_BATCH_REQUIRED");
        String format = frame.payload()[8] == 8 ? "Codec 8" : "Codec 8E";
        return new DeviceDescription("Teltonika", "gps_tracker", format, null);
    }

    /**
     * Mantém IMEI, buffer e quantidade de bytes esperada entre leituras do mesmo socket.
     */
    private static final class Session implements ProtocolSession {
        /** Armazena os bytes já recebidos da unidade de protocolo em montagem. */
        private final ByteBuffer buffer = ByteBuffer.allocate(TeltonikaAvl.MAX_PACKET);
        /** Identidade obtida no login desta conexão. */
        private String imei;
        /** Quantidade de bytes esperada para concluir a etapa atual do enquadramento. */
        // Etapas: 2 bytes de comprimento, 17 de login completo, 8 de cabeçalho
        // AVL e, por último, o tamanho total do lote informado pelo cabeçalho.
        private int target = 2;

        /**
         * Remonta login, cabeçalho e lote em etapas e só emite dados AVL após validar o pacote
         * completo.
         *
         * @param incoming bytes disponíveis nesta leitura TCP, possivelmente um fragmento
         * @return quadros completos nesta leitura, em ordem de recepção
         */
        @Override
        public List<ProtocolFrame> receive(byte[] incoming) {
            var frames = new ArrayList<ProtocolFrame>();
            int offset = 0;
            while (offset < incoming.length) {
                // Entre lotes completos, o FMB900 pode enviar FF como sinal de presença.
                if (imei != null && buffer.position() == 0 && incoming[offset] == (byte) 0xff) {
                    offset++;
                    continue;
                }
                // Uma leitura pode terminar no meio do cabeçalho ou conter vários lotes. Copie só até o limite da etapa.
                int amount = Math.min(incoming.length - offset, target - buffer.position());
                buffer.put(incoming, offset, amount);
                offset += amount;
                if (buffer.position() != target) continue;
                // ByteBuffer usa big-endian: os bytes mais significativos vêm primeiro.
                // Leituras por índice (getShort(0), getInt(4)) não mudam sua posição.
                if (imei == null && target == 2) {
                    if (buffer.getShort(0) != 15) throw new IllegalArgumentException("INVALID_IMEI_LENGTH");
                    target = 17;
                } else if (imei == null) {
                    imei = new String(buffer.array(), 2, 15, StandardCharsets.US_ASCII);
                    if (!imei.matches("[0-9]{15}")) throw new IllegalArgumentException("INVALID_IMEI");
                    frames.add(new ProtocolFrame(imei, new byte[0], "LOGIN", true));
                    // clear reinicia os índices para o próximo lote; não apaga o array.
                    buffer.clear();
                    target = 8;
                } else if (target == 8) {
                    int length = buffer.getInt(4);
                    if (buffer.getInt(0) != 0 || length < 3 || length > buffer.capacity() - 12) {
                        throw new IllegalArgumentException("INVALID_AVL_HEADER");
                    }
                    // O comprimento do corpo exclui os oito bytes iniciais e os quatro bytes finais de CRC.
                    target = length + 12;
                } else {
                    byte[] packet = java.util.Arrays.copyOf(buffer.array(), target);
                    // Só publica o quadro se estrutura e CRC passarem pela validação.
                    DECODER.decodePacket(packet, imei);
                    frames.add(new ProtocolFrame(imei, packet, "AVL", true));
                    buffer.clear();
                    target = 8;
                }
            }
            return frames;
        }

        /**
         * Produz um byte para confirmar o login ou quatro bytes com a quantidade de registros do
         * lote.
         *
         * @param frame quadro recebido para interpretação ou ingestão
         * @return bytes de resposta previstos pelo protocolo
         */
        @Override public byte[] acknowledge(ProtocolFrame frame) {
            // No lote: oito bytes de cabeçalho, um de codec, depois a contagem.
            // toUnsignedInt converte o byte Java (-128..127) para a faixa 0..255.
            return frame.isLogin() ? new byte[]{1}
                    : ByteBuffer.allocate(4).putInt(Byte.toUnsignedInt(frame.payload()[9])).array();
        }

        /**
         * Codifica a recusa de login com o byte zero previsto pelo protocolo.
         *
         * @return bytes de resposta previstos pelo protocolo
         */
        @Override public byte[] rejectLogin() { return new byte[]{0}; }
    }
}
