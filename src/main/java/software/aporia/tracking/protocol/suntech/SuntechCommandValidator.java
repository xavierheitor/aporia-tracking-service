
package software.aporia.tracking.protocol.suntech;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;

import software.aporia.tracking.protocol.CommandValidator;

/**
 * Valida a sintaxe de comandos Suntech e confere o destinatário quando o chamador informa a
 * identidade esperada.
 *
 * <p>Validar CMD/PRG não autoriza executar qualquer operação dessa gramática.
 * O dispatcher aplica uma política mais restrita antes da escrita TCP.</p>
 */
public final class SuntechCommandValidator
        implements CommandValidator {

    /** Tamanho máximo admitido para o payload do comando, em bytes. */
    private static final int MAX_LENGTH = 8192;

    /** Formato de dez dígitos exigido para o destinatário do comando. */
    private static final Pattern IDENTITY =
            Pattern.compile("\\d{10}");

    /** Formato do identificador de grupo no comando Suntech. */
    private static final Pattern GROUP =
            Pattern.compile("\\d{2}");

    /** Formato de um parâmetro de programação com código e valor. */
    private static final Pattern PARAMETER =
            Pattern.compile("\\d{2}#[\\x20-\\x7E]+");

    /**
     * Identifica o protocolo cuja gramática este validador aceita.
     *
     * @return identificador do protocolo desta implementação
     */
    @Override
    public @NonNull String protocolId() {
        return "suntech";
    }

    /**
     * Verifica a gramática do payload sem exigir uma identidade fornecida pelo chamador.
     *
     * @param payload bytes do conteúdo da mensagem
     */
    @Override
    public void validate(byte[] payload) {
        validate(payload, null);
    }

    /**
     * Valida cabeçalho, destinatário e parâmetros e compara a identidade quando informada.
     *
     * @param payload bytes do conteúdo da mensagem
     * @param expectedIdentity identidade de referência do equipamento
     */
    @Override
    public void validate(
            byte[] payload,
            String expectedIdentity
    ) {
        Objects.requireNonNull(payload, "payload");

        if (payload.length == 0 || payload.length > MAX_LENGTH) {
            throw invalid("INVALID_COMMAND_SIZE");
        }

        // Aceitamos CR como terminador opcional nesta etapa.
        // Descontar o terminador mantém a validação dos campos igual para os dois formatos de entrada.
        int length = payload.length;

        if (payload[length - 1] == '\r') {
            length--;
        }

        if (length == 0) {
            throw invalid("INVALID_COMMAND_FORMAT");
        }

        for (int i = 0; i < length; i++) {
            int value = Byte.toUnsignedInt(payload[i]);

            if (value < 32 || value > 126) {
                throw invalid("INVALID_COMMAND_ASCII");
            }
        }

        String command = new String(
                payload,
                0,
                length,
                StandardCharsets.US_ASCII
        );

        // Mantém os campos vazios para rejeitar um parâmetro omitido no final.
        String[] parts = command.split(";", -1);

        if (parts.length < 4) {
            throw invalid("INVALID_COMMAND_FORMAT");
        }

        String header = parts[0];
        String identity = parts[1];
        String group = parts[2];

        if (!header.equals("CMD") && !header.equals("PRG")) {
            throw invalid("UNSUPPORTED_COMMAND_HEADER");
        }

        if (!IDENTITY.matcher(identity).matches()) {
            throw invalid("INVALID_COMMAND_IDENTITY");
        }

        if (expectedIdentity != null
                && !expectedIdentity.equals(identity)) {
            throw invalid("COMMAND_IDENTITY_MISMATCH");
        }

        if (!GROUP.matcher(group).matches()) {
            throw invalid("INVALID_COMMAND_GROUP");
        }

        if (header.equals("CMD")) {
            validateCmd(parts);
        } else {
            validatePrg(parts);
        }
    }

    /**
     * Verifica o código da operação e a estrutura dos parâmetros de CMD.
     *
     * @param parts campos da mensagem separados por ponto e vírgula
     */
    private void validateCmd(String[] parts) {

        if (parts.length < 4) {
            throw invalid("INVALID_COMMAND_FORMAT");
        }

        // Código principal do comando.
        if (!parts[3].matches("\\d{2}")) {
            throw invalid("INVALID_COMMAND_CODE");
        }

        // Parâmetros adicionais variam por comando.
        for (int i = 4; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                throw invalid("INVALID_COMMAND_PARAMETER");
            }
        }
    }

    /**
     * Confere os campos que descrevem os parâmetros de programação PRG.
     *
     * @param parts campos da mensagem separados por ponto e vírgula
     */
    private void validatePrg(String[] parts) {

        if (parts.length < 4) {
            throw invalid("INVALID_COMMAND_FORMAT");
        }

        // Exemplo: PRG;ID;10;13#0
        // Alguns comandos podem alterar vários parâmetros.
        for (int i = 3; i < parts.length; i++) {

            if (!PARAMETER.matcher(parts[i]).matches()) {
                throw invalid("INVALID_PROGRAM_PARAMETER");
            }
        }
    }

    /**
     * Associa o motivo da rejeição à exceção devolvida ao chamador.
     *
     * @param code código que identifica o motivo da falha
     * @return exceção de formato usada para rejeitar o conteúdo
     */
    private IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
