
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
 * <p>Aceita os cabeçalhos {@code CMD} e {@code PRG}, identidade de dez dígitos e grupo de dois dígitos; o CR
 * final é opcional. Em {@code CMD}, o código da operação tem dois dígitos e os parâmetros extras não podem
 * ser vazios. Em {@code PRG}, cada parâmetro segue {@code código#valor}.</p>
 *
 * <p>Validar CMD/PRG não autoriza executar qualquer operação dessa gramática. O dispatcher aplica uma
 * política mais restrita, hoje limitada à consulta de firmware, antes da escrita TCP.</p>
 */
public final class SuntechCommandValidator
        implements CommandValidator {

    /** Tamanho máximo admitido para o payload do comando, em bytes; o limite é inclusivo. */
    private static final int MAX_LENGTH = 8192;

    /** Formato de dez dígitos exigido para o destinatário do comando. */
    private static final Pattern IDENTITY =
            Pattern.compile("\\d{10}");

    /** Formato do identificador de grupo no comando Suntech: exatamente dois dígitos. */
    private static final Pattern GROUP =
            Pattern.compile("\\d{2}");

    /** Formato de um parâmetro de programação: código de dois dígitos, {@code #} e valor. */
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
     * <p>O destinatário continua sendo validado quanto ao formato, mas não é comparado com o cadastro. Quem
     * precisa desse vínculo deve usar a sobrecarga com identidade.</p>
     *
     * @param payload bytes do conteúdo da mensagem
     * @throws IllegalArgumentException se o payload estiver fora da gramática aceita
     * @throws NullPointerException se {@code payload} for nulo
     */
    @Override
    public void validate(byte[] payload) {
        validate(payload, null);
    }

    /**
     * Valida cabeçalho, destinatário e parâmetros e compara a identidade quando informada.
     *
     * <p>A ordem das checagens define o código de erro observado: tamanho, ASCII, quantidade de campos,
     * cabeçalho, identidade, vínculo com a identidade esperada, grupo e, por fim, a gramática específica de
     * CMD ou PRG. O CR final é descartado antes dessas etapas.</p>
     *
     * @param payload bytes do conteúdo da mensagem
     * @param expectedIdentity identidade de referência do equipamento, ou {@code null} para não
     *        conferir o destinatário
     * @throws IllegalArgumentException se o payload violar qualquer regra da gramática
     * @throws NullPointerException se {@code payload} for nulo
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

        // A faixa imprimível evita bytes de controle e caracteres fora do ASCII no comando.
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
     * <p>O código precisa ter dois dígitos. Os campos seguintes são opacos para esta camada, mas um campo
     * vazio é rejeitado para não aceitar um parâmetro omitido.</p>
     *
     * @param parts campos da mensagem separados por ponto e vírgula
     * @throws IllegalArgumentException se o código ou algum parâmetro for inválido
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
     * <p>Cada campo a partir do índice 3 deve ser um par {@code código#valor}; o valor aceita espaço e
     * caracteres ASCII imprimíveis, mas não pode ser vazio.</p>
     *
     * @param parts campos da mensagem separados por ponto e vírgula
     * @throws IllegalArgumentException se algum parâmetro de programação for inválido
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
