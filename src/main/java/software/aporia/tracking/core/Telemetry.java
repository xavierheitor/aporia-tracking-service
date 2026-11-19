package software.aporia.tracking.core;

import java.time.Instant;

public record Telemetry(
        String deviceIdentity,
        Instant recordedAt,
        Double latitude,
        Double longitude,
        Double speedKph,
        Boolean ignition,
        Double heading,
        Integer satellites,
        Boolean validFix
        ) {

     public boolean hasPosition() {
        return recordedAt != null
                && latitude != null
                && longitude != null
                && Boolean.TRUE.equals(validFix);
    }

}
