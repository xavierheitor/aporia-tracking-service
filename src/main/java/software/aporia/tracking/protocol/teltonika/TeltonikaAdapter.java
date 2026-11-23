package software.aporia.tracking.protocol.teltonika;

import software.aporia.tracking.protocol.DeviceDescription;
import software.aporia.tracking.protocol.ProtocolAdapter;
import software.aporia.tracking.protocol.ProtocolFrame;
import software.aporia.tracking.protocol.ProtocolSession;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Conduz o login por IMEI e o enquadramento AVL em TCP. Cada conexão mantém um buffer próprio
 * para leituras fragmentadas.
 */
public final class TeltonikaAdapter implements ProtocolAdapter {
    private static final TeltonikaAvl DECODER = new TeltonikaAvl();

    @Override public boolean acceptsFirstByte(int first) { return first == 0; }

    @Override public String protocolId() { return "teltonika"; }
    @Override public String decoderVersion() { return DECODER.decoderVersion(); }
    @Override public ProtocolSession newSession() { return new Session(); }

    @Override
    public DeviceDescription describe(ProtocolFrame frame) {
        // A sessão só emite um quadro AVL após verificar a estrutura do lote e seu CRC.
        if (!"AVL".equals(frame.messageType())) throw new IllegalArgumentException("AVL_BATCH_REQUIRED");
        String format = frame.payload()[8] == 8 ? "Codec 8" : "Codec 8E";
        return new DeviceDescription("Teltonika", "gps_tracker", format, null);
    }

    private static final class Session implements ProtocolSession {
        private final ByteBuffer buffer = ByteBuffer.allocate(TeltonikaAvl.MAX_PACKET);
        private String imei;
        private int target = 2;

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
                int amount = Math.min(incoming.length - offset, target - buffer.position());
                buffer.put(incoming, offset, amount);
                offset += amount;
                if (buffer.position() != target) continue;
                if (imei == null && target == 2) {
                    if (buffer.getShort(0) != 15) throw new IllegalArgumentException("INVALID_IMEI_LENGTH");
                    target = 17;
                } else if (imei == null) {
                    imei = new String(buffer.array(), 2, 15, StandardCharsets.US_ASCII);
                    if (!imei.matches("[0-9]{15}")) throw new IllegalArgumentException("INVALID_IMEI");
                    frames.add(new ProtocolFrame(imei, new byte[0], "LOGIN", true));
                    buffer.clear();
                    target = 8;
                } else if (target == 8) {
                    int length = buffer.getInt(4);
                    if (buffer.getInt(0) != 0 || length < 3 || length > buffer.capacity() - 12) {
                        throw new IllegalArgumentException("INVALID_AVL_HEADER");
                    }
                    target = length + 12;
                } else {
                    byte[] packet = java.util.Arrays.copyOf(buffer.array(), target);
                    DECODER.decodePacket(packet, imei);
                    frames.add(new ProtocolFrame(imei, packet, "AVL", true));
                    buffer.clear();
                    target = 8;
                }
            }
            return frames;
        }

        @Override public byte[] acknowledge(ProtocolFrame frame) {
            return frame.isLogin() ? new byte[]{1}
                    : ByteBuffer.allocate(4).putInt(Byte.toUnsignedInt(frame.payload()[9])).array();
        }

        @Override public byte[] rejectLogin() { return new byte[]{0}; }
    }
}
