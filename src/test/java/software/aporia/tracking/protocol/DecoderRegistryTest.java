package software.aporia.tracking.protocol;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class DecoderRegistryTest {

    @Test
    void shouldResolveRegisteredDecoder() {

        SuntechDecoder suntech = new SuntechDecoder();

        DecoderRegistry registry = new DecoderRegistry(
                List.of(suntech)
        );

        assertSame(
                suntech,
                registry.get("suntech-ascii-v3")
        );
    }

    @Test
    void shouldRejectUnknownDecoder() {

        DecoderRegistry registry = new DecoderRegistry(
                List.of(new SuntechDecoder())
        );

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> registry.get("unknown-v1")
        );
        assertEquals("Decoder unavailable: unknown-v1", error.getMessage());
    }

    @Test
    void shouldRejectDuplicateDecoderVersions() {

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> new DecoderRegistry(
                        List.of(
                                new SuntechDecoder(),
                                new SuntechDecoder()
                        )
                )
        );
        assertEquals("Duplicate decoder version: suntech-ascii-v3", error.getMessage());
    }
}
