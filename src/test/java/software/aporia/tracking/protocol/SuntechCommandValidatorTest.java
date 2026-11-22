
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
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        ascii("RES;1234567890;03;04;1")
                )
        );
    }

    @Test
    void shouldRejectInvalidIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        ascii("CMD;ABC;03;04;1")
                )
        );
    }

    @Test
    void shouldRejectInvalidAscii() {
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        new byte[]{0, 1, 2}
                )
        );
    }

    @Test
    void shouldRejectMalformedProgramCommand() {
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        ascii("PRG;1234567890;16;70")
                )
        );
    }
}
