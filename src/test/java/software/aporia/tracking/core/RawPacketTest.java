package software.aporia.tracking.core;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class RawPacketTest {

    @Test
    void shouldCreateValidPacket() {
        RawPacket packet = new RawPacket(
                UUID.randomUUID(),
                "suntech",
                "123456789012345",
                new byte[]{0x01, 0x02, 0x03},
                Instant.now()
        );

        assertEquals("suntech", packet.protocol());
        assertEquals("123456789012345", packet.deviceIdentity());
        assertEquals(3, packet.payload().length);
    }

    @Test
    void shouldRejectEmptyPayload() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new RawPacket(
                        UUID.randomUUID(),
                        "suntech",
                        "123456789012345",
                        new byte[0],
                        Instant.now()
                )
        );

        assertEquals("Invalid payload size", exception.getMessage());
    }

    @Test
    void shouldProtectPayloadFromModification() {
        byte[] bytes = {0x01, 0x02};

        RawPacket packet = new RawPacket(
                UUID.randomUUID(),
                "suntech",
                "123456789012345",
                bytes,
                Instant.now()
        );

        bytes[0] = 0x05;

        assertEquals(0x01, packet.payload()[0]);

        byte[] returned = packet.payload();
        returned[0] = 0x06;

        assertEquals(0x06, returned[0]);
        assertEquals(0x01, packet.payload()[0]);
    }
}
