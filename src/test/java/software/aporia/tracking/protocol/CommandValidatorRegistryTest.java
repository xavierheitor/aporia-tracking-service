
package software.aporia.tracking.protocol;

import software.aporia.tracking.protocol.suntech.SuntechCommandValidator;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/**
 * Exercita a seleção do validador pelo protocolo, a verificação do destinatário e a rejeição de
 * registros duplicados ou de protocolos sem validador.
 *
 * <p>A seleção errada permitiria enviar um comando com a gramática de outro protocolo; por isso
 * o registro precisa ser explícito e único.</p>
 */
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
     *
     * <p>O payload é um CMD bem formado; a ausência de exceção confirma que o registro resolveu
     * a implementação correta.</p>
     */
    @Test
    void shouldValidateSuntechCommand() {

        // CMD;identidade;grupo;código;parâmetro: consulta de versão de firmware.
        byte[] payload = ascii(
                "CMD;1234567890;03;04;1\r"
        );

        assertDoesNotThrow(
                () -> registry.validate("suntech", payload)
        );
    }

    /**
     * Verifica o reconhecimento da sintaxe de programação pelo registro.
     *
     * <p>PRG compartilha o validador Suntech, mas usa pares código#valor em vez de um código de
     * operação.</p>
     */
    @Test
    void shouldValidateProgramCommand() {

        // PRG;identidade;grupo;código#valor: programação de um parâmetro.
        byte[] payload = ascii(
                "PRG;1234567890;16;70#60\r"
        );

        assertDoesNotThrow(
                () -> registry.validate("suntech", payload)
        );
    }

    /**
     * Confere o destinatário quando a chamada inclui a identidade esperada.
     *
     * <p>A sobrecarga com identidade deve aceitar o comando endereçado à própria sessão.</p>
     */
    @Test
    void shouldValidateDeviceIdentity() {

        byte[] payload = ascii(
                "CMD;1234567890;03;04;1\r"
        );

        // A identidade esperada coincide com o destinatário do comando.
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
     *
     * <p>Validar a sintaxe não basta: o comando não pode ser escrito na sessão de outra
     * identidade.</p>
     */
    @Test
    void shouldRejectDifferentDeviceIdentity() {

        // O comando é destinado a 1234567890, mas a sessão pertence a 0987654321.
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
     *
     * <p>O protocolo é textual; bytes de controle não podem ser interpretados como campos.</p>
     */
    @Test
    void shouldRejectInvalidAscii() {

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> registry.validate(
                        "suntech",
                        // Bytes de controle fora do ASCII imprimível aceito pela gramática.
                        new byte[]{0, 1, 2}
                )
        );
        assertEquals("INVALID_COMMAND_ASCII", error.getMessage());
    }

    /**
     * Confere a rejeição de uma mensagem com estrutura de comando inválida.
     *
     * <p>Um cabeçalho sem os quatro campos mínimos não pode ser confundido com comando.</p>
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
     *
     * <p>Falhar na seleção evita validar bytes com uma gramática diferente da esperada.</p>
     */
    @Test
    void shouldRejectUnknownProtocol() {

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> registry.validate(
                        "teltonika",
                        // Não há validador Teltonika registrado nesta instância.
                        ascii("CMD;1234567890;03;04;1")
                )
        );
        assertEquals("UNSUPPORTED_COMMAND_PROTOCOL: teltonika", error.getMessage());
    }

    /**
     * Impede que dois validadores disputem o mesmo identificador de protocolo.
     *
     * <p>A escolha implícita pelo primeiro registro tornaria o envio dependente da ordem da
     * lista.</p>
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
                // A sobrecarga com identidade deve falhar na seleção antes de olhar o payload.
                () -> registry.validate("teltonika", ascii("CMD;1234567890;03;04;1"), "1234567890"));
        assertEquals("UNSUPPORTED_COMMAND_PROTOCOL: teltonika", error.getMessage());
    }

}
