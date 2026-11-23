
package software.aporia.tracking.protocol.suntech;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Cobre a gramática dos comandos e parâmetros Suntech e os formatos rejeitados. */
class SuntechCommandValidatorTest {

    /** Validador usado para exercitar a gramática dos comandos. */
    private final SuntechCommandValidator validator =
            new SuntechCommandValidator();

    /**
     * Codifica a mensagem de teste com o conjunto de caracteres do protocolo.
     *
     * @param value valor a interpretar
     * @return bytes ASCII da mensagem de exemplo
     */
    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Verifica a gramática da consulta de versão de firmware.
     */
    @Test
    void shouldAcceptFirmwareVersionRequest() {
        assertDoesNotThrow(() -> validator.validate(
                ascii("CMD;1234567890;03;04;1"),
                "1234567890"
        ));
    }

    /**
     * Exercita a sintaxe da consulta de parâmetros predefinidos.
     */
    @Test
    void shouldAcceptPresetRequest() {
        assertDoesNotThrow(() -> validator.validate(
                ascii("CMD;1234567890;03;06;0"),
                "1234567890"
        ));
    }

    /**
     * Verifica a estrutura de uma mensagem de programação.
     */
    @Test
    void shouldAcceptProgramCommand() {
        assertDoesNotThrow(() -> validator.validate(
                ascii("PRG;1234567890;16;70#60"),
                "1234567890"
        ));
    }

    /**
     * Aceita vários parâmetros na mesma mensagem de programação.
     */
    @Test
    void shouldAcceptMultipleParameters() {
        assertDoesNotThrow(() -> validator.validate(
                ascii("PRG;1234567890;16;70#60;71#100"),
                "1234567890"
        ));
    }

    /**
     * Confere que o validador aceita o terminador CR opcional.
     */
    @Test
    void shouldAcceptOptionalCarriageReturn() {
        assertDoesNotThrow(() -> validator.validate(
                ascii("CMD;1234567890;03;04;1\r"),
                "1234567890"
        ));
    }

    /**
     * Rejeita um destinatário diferente do esperado.
     */
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

    /**
     * Rejeita um cabeçalho fora dos formatos de comando aceitos.
     */
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

    /**
     * Rejeita uma identidade que não segue o formato do protocolo.
     */
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

    /**
     * Confere a rejeição de bytes fora da faixa ASCII admitida.
     */
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

    /**
     * Rejeita a programação com parâmetros malformados.
     */
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
