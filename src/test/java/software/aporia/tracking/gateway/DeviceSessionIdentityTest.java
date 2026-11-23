package software.aporia.tracking.gateway;

import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/** Verifica que uma conexão permanece vinculada à identidade declarada no início da sessão. */
class DeviceSessionIdentityTest {

    /**
     * Monta a sessão com uma identidade conhecida para testar a validação.
     *
     * @return sessão vinculada à identidade de teste
     */
    private DeviceSession createSession() {
        return new DeviceSession(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "suntech",
                "1234567890",
                new Socket()
        );
    }

    /**
     * Permite a continuidade da conexão com a mesma identidade.
     */
    @Test
    void shouldAcceptSameIdentity() {
        DeviceSession session = createSession();

        assertDoesNotThrow(
                () -> session.validateIdentity("1234567890")
        );
    }

    /**
     * Rejeita a mudança de identidade durante a conexão.
     */
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

    /**
     * Rejeita identidade ausente dentro de uma sessão vinculada.
     */
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
