package software.aporia.tracking.protocol;

import java.util.List;
import software.aporia.tracking.core.Telemetry;

public record DecodedPacket(String identity, List<Telemetry> readings, String responseType) {
    public DecodedPacket {
        readings = List.copyOf(readings);
    }
}
