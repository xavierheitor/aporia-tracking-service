package software.aporia.tracking.protocol;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class ProtocolRegistryTest {

    @Test
    void shouldResolveRegisteredAdapter() {

        SuntechAdapter suntech = new SuntechAdapter();

        ProtocolRegistry registry = new ProtocolRegistry(
                List.of(suntech)
        );

        assertSame(
                suntech,
                registry.get("suntech")
        );
    }

    @Test
    void shouldRejectUnknownProtocol() {

        ProtocolRegistry registry = new ProtocolRegistry(
                List.of(new SuntechAdapter())
        );

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> registry.get("unknown")
        );
        assertEquals("Unsupported protocol: unknown", error.getMessage());
    }

    @Test
    void shouldRejectDuplicateProtocolIds() {

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> new ProtocolRegistry(
                        List.of(
                                new SuntechAdapter(),
                                new SuntechAdapter()
                        )
                )
        );
        assertEquals("Duplicate protocol: suntech", error.getMessage());
    }
}
