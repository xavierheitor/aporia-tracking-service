package software.aporia.tracking.protocol;

public interface ProtocolAdapter {

    String protocolId();

    String decoderVersion();

    boolean acceptsFirstByte(int first);

    ProtocolSession newSession();
}