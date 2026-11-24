
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

    /** Registro de implementações exercitado pelo cenário. */
    private final CommandValidatorRegistry registry =
            new CommandValidatorRegistry(
                    List.of(new SuntechCommandValidator())
            );

    /**
     * Converte a mensagem de exemplo nos bytes ASCII transmitidos pelo rastreador.
     *
     * @param value valor a interpretar
     * @return bytes ASCII da mensagem de exemplo
     */
    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Valida o comando usando o validador registrado para Suntech.
     */
    @Test
    void shouldValidateSuntechCommand() {

        byte[] payload = ascii(
                "CMD;1234567890;03;04;1\r"
        );

        assertDoesNotThrow(
                () -> registry.validate("suntech", payload)
        );
    }

    /**
     * Verifica o reconhecimento da sintaxe de programação pelo registro.
     */
    @Test
    void shouldValidateProgramCommand() {

        byte[] payload = ascii(
                "PRG;1234567890;16;70#60\r"
        );

        assertDoesNotThrow(
                () -> registry.validate("suntech", payload)
        );
    }

    /**
     * Confere o destinatário quando a chamada inclui a identidade esperada.
     */
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

    /**
     * Rejeita um comando endereçado a outro dispositivo.
     */
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

    /**
     * Rejeita bytes incompatíveis com o formato ASCII do comando.
     */
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

    /**
     * Confere a rejeição de uma mensagem com estrutura de comando inválida.
     */
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

    /**
     * Verifica o erro ao solicitar um protocolo sem validador.
     */
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

    /**
     * Impede que dois validadores disputem o mesmo identificador de protocolo.
     */
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
    /** As duas sobrecargas devem rejeitar protocolos sem validador da mesma forma. */
    @Test
    void shouldRejectUnknownProtocolWhenIdentityIsProvided() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> registry.validate("teltonika", ascii("CMD;1234567890;03;04;1"), "1234567890"));
        assertEquals("UNSUPPORTED_COMMAND_PROTOCOL: teltonika", error.getMessage());
    }

}
