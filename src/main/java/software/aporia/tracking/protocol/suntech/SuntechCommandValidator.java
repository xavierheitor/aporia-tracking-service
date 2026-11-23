
package software.aporia.tracking.protocol.suntech;

import software.aporia.tracking.protocol.CommandValidator;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Valida a sintaxe de comandos Suntech e confere o destinatário quando o chamador informa a
 * identidade esperada.
 */
public final class SuntechCommandValidator
        implements CommandValidator {

    private static final int MAX_LENGTH = 8192;

    private static final Pattern IDENTITY =
            Pattern.compile("\\d{10}");

    private static final Pattern GROUP =
            Pattern.compile("\\d{2}");

    private static final Pattern PARAMETER =
            Pattern.compile("\\d{2}#[\\x20-\\x7E]+");

    @Override
    public String protocolId() {
        return "suntech";
    }

    @Override
    public void validate(byte[] payload) {
        validate(payload, null);
    }

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
        // O enquadramento exato no TCP ainda precisa ser confirmado.
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

    private IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
