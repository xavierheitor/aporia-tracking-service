package software.aporia.tracking.gateway;

import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class DeviceSessionIdentityTest {

    private DeviceSession createSession() {
        return new DeviceSession(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "suntech",
                "1234567890",
                new Socket()
        );
    }

    @Test
    void shouldAcceptSameIdentity() {
        DeviceSession session = createSession();

        assertDoesNotThrow(
                () -> session.validateIdentity("1234567890")
        );
    }

    @Test
    void shouldRejectDifferentIdentity() {
        DeviceSession session = createSession();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> session.validateIdentity("0987654321")
        );

        assertEquals(
                "SESSION_IDENTITY_MISMATCH",
                error.getMessage()
        );
    }

    @Test
    void shouldRejectNullIdentity() {
        DeviceSession session = createSession();

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> session.validateIdentity(null)
        );
        assertEquals("SESSION_IDENTITY_MISMATCH", error.getMessage());
    }
}
