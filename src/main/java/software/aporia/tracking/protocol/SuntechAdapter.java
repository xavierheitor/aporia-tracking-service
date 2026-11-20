package software.aporia.tracking.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class SuntechAdapter implements ProtocolAdapter {

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
