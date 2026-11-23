
package software.aporia.tracking.protocol;

import software.aporia.tracking.protocol.suntech.SuntechCommandValidator;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/** Exercita a seleção do validador e a verificação do destinatário de comandos Suntech. */
class CommandValidatorRegistryTest {

    private final CommandValidatorRegistry registry =
            new CommandValidatorRegistry(
                    List.of(new SuntechCommandValidator())
            );

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void shouldValidateSuntechCommand() {

        byte[] payload = ascii(
                "CMD;1234567890;03;04;1\r"
        );

        assertDoesNotThrow(
                () -> registry.validate("suntech", payload)
        );
    }

    @Test
    void shouldValidateProgramCommand() {

        byte[] payload = ascii(
                "PRG;1234567890;16;70#60\r"
        );

        assertDoesNotThrow(
                () -> registry.validate("suntech", payload)
        );
    }

    @Test
    void shouldValidateDeviceIdentity() {

        byte[] payload = ascii(
                "CMD;1234567890;03;04;1\r"
        );

        assertDoesNotThrow(
                () -> registry.validate(
                        "suntech",
                        payload,
                        "1234567890"
                )
        );
    }

    @Test
    void shouldRejectDifferentDeviceIdentity() {

        byte[] payload = ascii(
                "CMD;1234567890;03;04;1\r"
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> registry.validate(
                        "suntech",
                        payload,
                        "0987654321"
                )
        );

        assertEquals(
                "COMMAND_IDENTITY_MISMATCH",
                error.getMessage()
        );
    }

    @Test
    void shouldRejectInvalidAscii() {

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> registry.validate(
                        "suntech",
                        new byte[]{0, 1, 2}
                )
        );
        assertEquals("INVALID_COMMAND_ASCII", error.getMessage());
    }

    @Test
    void shouldRejectMalformedCommand() {

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> registry.validate(
                        "suntech",
                        ascii("TEST\r")
                )
        );
        assertEquals("INVALID_COMMAND_FORMAT", error.getMessage());
    }

    @Test
    void shouldRejectUnknownProtocol() {

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> registry.validate(
                        "teltonika",
                        ascii("CMD;1234567890;03;04;1")
                )
        );
        assertEquals("UNSUPPORTED_COMMAND_PROTOCOL: teltonika", error.getMessage());
    }

    @Test
    void shouldRejectDuplicateValidators() {

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> new CommandValidatorRegistry(
                        List.of(
                                new SuntechCommandValidator(),
                                new SuntechCommandValidator()
                        )
                )
        );
        assertEquals("DUPLICATE_COMMAND_VALIDATOR: suntech", error.getMessage());
    }
}
