package software.aporia.tracking.protocol;

public interface ProtocolAdapter {

    String protocolId();

    String decoderVersion();

    ProtocolSession newSession();
}