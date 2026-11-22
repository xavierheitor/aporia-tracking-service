
package software.aporia.tracking.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

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

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.validate(
                        "suntech",
                        new byte[]{0, 1, 2}
                )
        );
    }

    @Test
    void shouldRejectMalformedCommand() {

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.validate(
                        "suntech",
                        ascii("TEST\r")
                )
        );
    }

    @Test
    void shouldRejectUnknownProtocol() {

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.validate(
                        "teltonika",
                        ascii("CMD;1234567890;03;04;1")
                )
        );
    }

    @Test
    void shouldRejectDuplicateValidators() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new CommandValidatorRegistry(
                        List.of(
                                new SuntechCommandValidator(),
                                new SuntechCommandValidator()
                        )
                )
        );
    }
}
