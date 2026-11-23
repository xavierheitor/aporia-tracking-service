
package software.aporia.tracking.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SuntechCommandValidatorTest {

    private final SuntechCommandValidator validator =
            new SuntechCommandValidator();

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void shouldAcceptFirmwareVersionRequest() {
        assertDoesNotThrow(() -> validator.validate(
                ascii("CMD;1234567890;03;04;1"),
                "1234567890"
        ));
    }

    @Test
    void shouldAcceptPresetRequest() {
        assertDoesNotThrow(() -> validator.validate(
                ascii("CMD;1234567890;03;06;0"),
                "1234567890"
        ));
    }

    @Test
    void shouldAcceptProgramCommand() {
        assertDoesNotThrow(() -> validator.validate(
                ascii("PRG;1234567890;16;70#60"),
                "1234567890"
        ));
    }

    @Test
    void shouldAcceptMultipleParameters() {
        assertDoesNotThrow(() -> validator.validate(
                ascii("PRG;1234567890;16;70#60;71#100"),
                "1234567890"
        ));
    }

    @Test
    void shouldAcceptOptionalCarriageReturn() {
        assertDoesNotThrow(() -> validator.validate(
                ascii("CMD;1234567890;03;04;1\r"),
                "1234567890"
        ));
    }

    @Test
    void shouldRejectDifferentIdentity() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        ascii("CMD;1234567890;03;04;1"),
                        "0987654321"
                )
        );

        assertEquals(
                "COMMAND_IDENTITY_MISMATCH",
                error.getMessage()
        );
    }

    @Test
    void shouldRejectUnsupportedHeader() {
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        ascii("RES;1234567890;03;04;1")
                )
        );
        assertEquals("UNSUPPORTED_COMMAND_HEADER", error.getMessage());
    }

    @Test
    void shouldRejectInvalidIdentity() {
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        ascii("CMD;ABC;03;04;1")
                )
        );
        assertEquals("INVALID_COMMAND_IDENTITY", error.getMessage());
    }

    @Test
    void shouldRejectInvalidAscii() {
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        new byte[]{0, 1, 2}
                )
        );
        assertEquals("INVALID_COMMAND_ASCII", error.getMessage());
    }

    @Test
    void shouldRejectMalformedProgramCommand() {
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        ascii("PRG;1234567890;16;70")
                )
        );
        assertEquals("INVALID_PROGRAM_PARAMETER", error.getMessage());
    }
}
