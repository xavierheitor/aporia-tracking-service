package software.aporia.tracking.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.core.Telemetry;

class DecoderRegistryAlternativeTest {

    @Test
    void shouldUseAlternativeDecoder() {

        TelemetryDecoder alternative = new TelemetryDecoder() {

            @Override
            public String decoderVersion() {
                return "example-protocol-v1";
            }

            @Override
            public Telemetry decode(byte[] frame) {
                return new Telemetry(
                        "1234567890",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );
            }
        };

        DecoderRegistry registry = new DecoderRegistry(
                List.of(
                        new SuntechDecoder(),
                        alternative
                )
        );

        TelemetryDecoder selected
                = registry.get("example-protocol-v1");

        Telemetry telemetry = selected.decode(
                "EXAMPLE".getBytes(StandardCharsets.US_ASCII)
        );

        assertSame(alternative, selected);
        assertEquals(
                "1234567890",
                telemetry.deviceIdentity()
        );
    }
}
