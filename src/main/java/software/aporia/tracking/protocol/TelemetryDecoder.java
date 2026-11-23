package software.aporia.tracking.protocol;

public interface TelemetryDecoder {

    String protocolId();

    String decoderVersion();

    DecodedPacket decodePacket(byte[] frame, String registeredIdentity);
}
