package software.aporia.tracking.protocol.suntech;

import software.aporia.tracking.protocol.DeviceDescription;
import software.aporia.tracking.protocol.ProtocolAdapter;
import software.aporia.tracking.protocol.ProtocolFrame;
import software.aporia.tracking.protocol.ProtocolSession;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapta os quadros ASCII Suntech ao contrato do gateway e extrai o modelo quando o mapa de
 * campos o inclui.
 */
public final class SuntechAdapter implements ProtocolAdapter {

    @Override public boolean acceptsFirstByte(int first) { return first == 'S' || first == 'A' || first == 'R'; }

    @Override
    public String protocolId() {
        return "suntech";
    }

    @Override
    public String decoderVersion() {
        return "suntech-ascii-v3";
    }

    @Override
    public ProtocolSession newSession() {
        return new SuntechSession();
    }

    @Override
    public DeviceDescription describe(ProtocolFrame frame) {
        byte[] payload = frame.payload();
        new SuntechDecoder().decodePacket(payload, frame.deviceIdentity());
        String[] fields = new String(payload, 0, payload.length - 1, StandardCharsets.US_ASCII).split(";", -1);
        boolean embedded = "RES".equals(fields[0]) && fields.length > 2
                && ("STT".equals(fields[1]) || "ASTT".equals(fields[1]));
        String model = null;
        if (embedded || "STT".equals(fields[0]) || "ASTT".equals(fields[0])) {
            int mapIndex = embedded ? 3 : 2;
            if ((Integer.parseInt(fields[mapIndex], 16) & 2) != 0 && fields[mapIndex + 1].matches("[0-9]{1,10}")) {
                model = fields[mapIndex + 1];
            }
        } else if ("RES".equals(fields[0]) || "RPR".equals(fields[0])) {
            if (fields.length < 4 || !fields[3].matches("[0-9]{2}")
                    || ("RES".equals(fields[0]) && !fields[2].matches("[0-9]{2}"))) {
                throw new IllegalArgumentException("INVALID_DISCOVERY_RESPONSE");
            }
        }
        return new DeviceDescription("Suntech", "gps_tracker", "Suntech ASCII", model);
    }

    private static final class SuntechSession
            implements ProtocolSession {

        private final SuntechProtocol protocol
                = new SuntechProtocol();

        @Override
        public List<ProtocolFrame> receive(byte[] incoming) {

            List<ProtocolFrame> frames = new ArrayList<>();

            for (byte[] payload : protocol.receive(incoming)) {

                String identity = protocol.identify(payload);

                String message = new String(
                        payload,
                        StandardCharsets.US_ASCII
                );

                int separator = message.indexOf(';');

                String messageType
                        = message.substring(0, separator);

                frames.add(new ProtocolFrame(
                        identity,
                        payload,
                        messageType,
                        false
                ));
            }

            return frames;
        }
    }
}
