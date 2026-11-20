package software.aporia.tracking.protocol;

import software.aporia.tracking.core.Telemetry;

public interface TelemetryDecoder {

    String decoderVersion();

    Telemetry decode(byte[] frame);
}
