package software.aporia.tracking.protocol;

import java.util.List;

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

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.get("unknown")
        );
    }

    @Test
    void shouldRejectDuplicateProtocolIds() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProtocolRegistry(
                        List.of(
                                new SuntechAdapter(),
                                new SuntechAdapter()
                        )
                )
        );
    }
}
