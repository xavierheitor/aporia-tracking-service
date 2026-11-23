package software.aporia.tracking.protocol;

import software.aporia.tracking.protocol.suntech.SuntechDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.core.Telemetry;

/** Demonstra o registro de um decodificador alternativo pela versão. */
class DecoderRegistryAlternativeTest {

    @Test
    void shouldUseAlternativeDecoder() {

        TelemetryDecoder alternative = new TelemetryDecoder() {

            @Override public String protocolId() { return "example"; }

            @Override
            public String decoderVersion() {
                return "example-protocol-v1";
            }

            @Override
            public DecodedPacket decodePacket(byte[] frame, String identity) {
                return new DecodedPacket(identity, List.of(new Telemetry(
                        "1234567890",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )), null);
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

        Telemetry telemetry = selected.decodePacket(
                "EXAMPLE".getBytes(StandardCharsets.US_ASCII), "1234567890"
        ).readings().getFirst();

        assertSame(alternative, selected);
        assertEquals(
                "1234567890",
                telemetry.deviceIdentity()
        );
    }
}
