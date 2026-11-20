package software.aporia.tracking.protocol;

import java.util.List;

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

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.get("unknown-v1")
        );
    }

    @Test
    void shouldRejectDuplicateDecoderVersions() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new DecoderRegistry(
                        List.of(
                                new SuntechDecoder(),
                                new SuntechDecoder()
                        )
                )
        );
    }
}
