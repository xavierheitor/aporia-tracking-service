
package software.aporia.tracking.protocol.suntech;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Cobre a gramática dos comandos e parâmetros Suntech e os formatos rejeitados.
 *
 * <p>Validar a sintaxe não autoriza a execução: o dispatcher aplica uma política mais restrita
 * antes da escrita, mas um payload malformado não pode chegar ao socket.</p>
 */
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
     *
     * <p>CMD usa identidade, grupo, código de operação e parâmetro; o cenário cobre todos os
     * campos obrigatórios.</p>
     */
    @Test
    void shouldAcceptFirmwareVersionRequest() {
        // CMD;identidade;grupo 03;código 04;parâmetro 1 — consulta de versão de firmware.
        assertDoesNotThrow(() -> validator.validate(
                ascii("CMD;1234567890;03;04;1"),
                "1234567890"
        ));
    }

    /**
     * Exercita a sintaxe da consulta de parâmetros predefinidos.
     *
     * <p>O mesmo formato CMD deve aceitar outros códigos de operação.</p>
     */
    @Test
    void shouldAcceptPresetRequest() {
        // Código 06 consulta os parâmetros predefinidos; o parâmetro 0 é aceito como texto.
        assertDoesNotThrow(() -> validator.validate(
                ascii("CMD;1234567890;03;06;0"),
                "1234567890"
        ));
    }

    /**
     * Verifica a estrutura de uma mensagem de programação.
     *
     * <p>PRG substitui o código de operação por pares código#valor.</p>
     */
    @Test
    void shouldAcceptProgramCommand() {
        // PRG;identidade;grupo 16;par 70#60 — um par código#valor.
        assertDoesNotThrow(() -> validator.validate(
                ascii("PRG;1234567890;16;70#60"),
                "1234567890"
        ));
    }

    /**
     * Aceita vários parâmetros na mesma mensagem de programação.
     *
     * <p>A gramática precisa permitir mais de um par código#valor separado por ponto e vírgula.</p>
     */
    @Test
    void shouldAcceptMultipleParameters() {
        // Vários pares código#valor são separados por ponto e vírgula.
        assertDoesNotThrow(() -> validator.validate(
                ascii("PRG;1234567890;16;70#60;71#100"),
                "1234567890"
        ));
    }

    /**
     * Confere que o validador aceita o terminador CR opcional.
     *
     * <p>O CR não pertence aos campos; removê-lo antes do parse mantém as duas formas
     * equivalentes.</p>
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
     *
     * <p>O comando não pode ser escrito na sessão de outra identidade.</p>
     */
    @Test
    void shouldRejectDifferentIdentity() {
        // O payload pertence a 1234567890, mas a sessão espera 0987654321.
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
     *
     * <p>RES é resposta, não comando; o validador só aceita CMD e PRG.</p>
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
     *
     * <p>ABC não tem os dez dígitos exigidos e não pode endereçar o equipamento.</p>
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
     *
     * <p>O comando é textual; bytes de controle não podem ser interpretados como campos.</p>
     */
    @Test
    void shouldRejectInvalidAscii() {
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(
                        // 0x00 e 0x01 estão fora do ASCII imprimível aceito.
                        new byte[]{0, 1, 2}
                )
        );
        assertEquals("INVALID_COMMAND_ASCII", error.getMessage());
    }

    /**
     * Rejeita a programação com parâmetros malformados.
     *
     * <p>Falta o separador # entre código e valor no parâmetro de programação.</p>
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
