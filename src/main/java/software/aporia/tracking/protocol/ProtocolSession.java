package software.aporia.tracking.protocol;

import java.util.List;

public interface ProtocolSession {

    List<ProtocolFrame> receive(byte[] incoming);

    default byte[] acknowledge(ProtocolFrame frame) {
        return new byte[0];
    }
}
