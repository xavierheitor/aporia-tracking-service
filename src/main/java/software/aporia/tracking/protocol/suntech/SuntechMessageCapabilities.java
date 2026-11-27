package software.aporia.tracking.protocol.suntech;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;

/**
 * Identifica as capacidades combinadas de uma mensagem Suntech a partir de seu conteúdo.
 *
 * <p>A leitura considera apenas o cabeçalho e, no caso de {@code RES}, o cabeçalho embutido. Não valida
 * identidade, mapa nem campos de telemetria: mensagens estruturalmente inválidas ainda podem ser
 * classificadas e serão rejeitadas pelo decodificador. Um cabeçalho desconhecido ou um payload vazio resulta
 * em conjunto vazio, não em exceção.</p>
 */
public final class SuntechMessageCapabilities {

    /**
     * Restringe a classificação ao método estático, que interpreta cada mensagem
     * independentemente.
     */
    private SuntechMessageCapabilities() {
    }

    /**
     * Reconhece os conteúdos que podem coexistir no mesmo quadro.
     *
     * @param payload bytes do conteúdo da mensagem
     * @return conjunto imutável de capacidades reconhecidas, possivelmente vazio
     */
    public static ProtocolMessageCapabilities classify(
            byte[] payload
    ) {

        if (payload == null || payload.length == 0) {
            return new ProtocolMessageCapabilities(Set.of());
        }

        // strip remove o CR final e espaços; split com -1 preserva campos vazios, como o do RES.
        String message = new String(
                payload,
                StandardCharsets.US_ASCII
        ).strip();

        String[] fields = message.split(";", -1);

        // Defensivo: split(";", -1) sempre devolve ao menos um campo, mesmo para texto vazio.
        if (fields.length == 0) {
            return new ProtocolMessageCapabilities(Set.of());
        }

        EnumSet<MessageCapability> capabilities
                = EnumSet.noneOf(MessageCapability.class);

        switch (fields[0]) {

            case "STT", "ASTT" ->
                capabilities.add(MessageCapability.TELEMETRY);

            case "ALV" ->
                capabilities.add(MessageCapability.HEARTBEAT);

            case "RPR" ->
                capabilities.add(MessageCapability.COMMAND_RESPONSE);

            // RES pode trazer uma posição embutida e ainda precisa aparecer no histórico de respostas.
            case "RES" -> {
                capabilities.add(MessageCapability.COMMAND_RESPONSE);

                if (fields.length > 1
                        && ("STT".equals(fields[1])
                        || "ASTT".equals(fields[1]))) {

                    capabilities.add(MessageCapability.TELEMETRY);
                }
            }

            default -> {
                // Cabeçalho ainda não reconhecido: o roteador decide se a ausência de trabalho é erro.
            }
        }

        return new ProtocolMessageCapabilities(capabilities);
    }
}
