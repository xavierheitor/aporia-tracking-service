package software.aporia.tracking.protocol.suntech;

import software.aporia.tracking.protocol.DecodedPacket;
import software.aporia.tracking.protocol.TelemetryDecoder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import software.aporia.tracking.core.Telemetry;

/**
 * Interpreta mensagens Suntech, incluindo posições com mapa de campos, heartbeats e respostas com
 * telemetria embutida.
 */
public class SuntechDecoder implements TelemetryDecoder {

    /**
     * Identifica a família dos quadros interpretados pelo decodificador.
     *
     * @return identificador do protocolo desta implementação
     */
    @Override public String protocolId() { return "suntech"; }

    /**
     * Identifica a versão do formato gravada com os pacotes Suntech.
     *
     * @return versão usada para interpretar os bytes persistidos
     */
    @Override
    public String decoderVersion() {
        return "suntech-ascii-v3";
    }

    /** Nomes dos campos na ordem dos bits do mapa de presença. */
    private static final String[] FIELDS = {
        "map", "model", "firmware", "realtime",
        "date", "time", "cell", "mcc",
        "mnc", "lac", "signal", "lat",
        "lon", "speed", "heading", "satellites",
        "fix", "inputs", "outputs", "mode",
        "reason", "sequence"
    };

    /** Formato decimal aceito antes da conversão dos campos numéricos. */
    private static final Pattern NUMBER = Pattern.compile(
            "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)"
    );

    /** Enquadrador Suntech usado para reconhecer a identidade do quadro. */
    private final SuntechProtocol protocol = new SuntechProtocol();

    /**
     * Combina o plano de roteamento com a leitura de telemetria, preservando o tipo de resposta
     * em mensagens compostas.
     *
     * @param frame quadro recebido para interpretação ou ingestão
     * @param registeredIdentity identidade de referência do equipamento
     * @return identidade, leituras e eventual resposta presentes no pacote
     */
    @Override
    public DecodedPacket decodePacket(byte[] frame, String registeredIdentity) {
        String identity = protocol.identify(frame);
        var plan = new SuntechMessageRouter().route(frame);
        var readings = plan.processTelemetry() || plan.processHeartbeat()
                ? java.util.List.of(decode(frame)) : java.util.List.<Telemetry>of();
        String response = plan.processCommandResponse() ? new String(frame, 0, 3, StandardCharsets.US_ASCII) : null;
        return new DecodedPacket(identity, readings, response);
    }

    /**
     * Extrai a telemetria de uma mensagem de posição ou presença, inclusive de uma posição
     * embutida em resposta.
     *
     * @param frame quadro recebido para interpretação ou ingestão
     * @return telemetria interpretada a partir dos campos do equipamento
     */
    public Telemetry decode(byte[] frame) {

        String identity = protocol.identify(frame);

        String message = new String(
                frame,
                0,
                frame.length - 1,
                StandardCharsets.US_ASCII
        );

        String[] parts = message.split(";", -1);

        return switch (parts[0]) {

            case "ALV" -> {
                if (parts.length != 2) {
                    throw invalid("INVALID_FIELD_COUNT");
                }

                yield new Telemetry(
                identity,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
                );
            }

            case "STT", "ASTT" ->
                decodePosition(identity, parts);

            case "RES" -> {

                if (parts.length > 2
                        && ("STT".equals(parts[1])
                        || "ASTT".equals(parts[1]))) {

                    String[] embedded
                            = java.util.Arrays.copyOfRange(
                                    parts,
                                    1,
                                    parts.length
                            );

                    yield decodePosition(identity, embedded);
                }

                throw new UnsupportedOperationException(
                        "COMMAND_RESPONSE_WITHOUT_TELEMETRY"
                );
            }

            default ->
                throw invalid("UNSUPPORTED_MESSAGE");
        };
    }

    /**
     * Percorre o mapa de presença para associar cada campo à sua posição no texto e validar os
     * valores da leitura.
     *
     * @param identity identidade declarada pelo rastreador no protocolo
     * @param parts campos da mensagem separados por ponto e vírgula
     * @return telemetria interpretada a partir dos campos do equipamento
     */
    private Telemetry decodePosition(
            String identity,
            String[] parts
    ) {
        if (parts.length < 3
                || !parts[2].matches("[0-9a-fA-F]{6}")) {
            throw invalid("INLINE_MAP_REQUIRED");
        }

        int map = Integer.parseInt(parts[2], 16);

        if ((map & 1) == 0 || (map & ~0xBFFFFF) != 0) {
            throw invalid("UNSUPPORTED_MAP");
        }

        Map<String, String> values = new HashMap<>();
        int index = 2;

        // Cada bit presente consome um campo. Um bit ausente não ocupa posição no texto recebido.
        for (int bit = 0; bit < FIELDS.length; bit++) {
            if ((map & (1 << bit)) != 0) {
                if (index >= parts.length) {
                    throw invalid("INVALID_FIELD_COUNT");
                }
                values.put(FIELDS[bit], parts[index++]);
            }
        }

        if ((map & 0x800000) != 0) {
            if (index >= parts.length || !parts[index].matches("[0-9a-fA-F]{8}")) throw invalid("INVALID_EXTENSION_MAP");
            int fields = Long.bitCount(Long.parseLong(parts[index++], 16));
            index += fields;
        }

        // O protocolo permite um delimitador ; opcional
        // imediatamente antes do CR.
        if (index != parts.length
                && !(index == parts.length - 1
                && parts[index].isEmpty())) {
            throw invalid("INVALID_FIELD_COUNT");
        }

        Instant recordedAt = null;

        String date = values.get("date");
        String time = values.get("time");

        if (date != null || time != null) {
            if (date == null || !date.matches("\\d{8}")
                    || time == null) {
                throw invalid("INVALID_DATE");
            }

            try {
                String iso = date.substring(0, 4) + "-"
                        + date.substring(4, 6) + "-"
                        + date.substring(6, 8) + "T"
                        + time + "Z";

                recordedAt = Instant.parse(iso);

            } catch (DateTimeParseException e) {
                throw invalid("INVALID_DATE");
            }
        }

        Double latitude = number(values.get("lat"), -90, 90);
        Double longitude = number(values.get("lon"), -180, 180);

        if ((latitude == null) != (longitude == null)) {
            throw invalid("INCOMPLETE_COORDINATES");
        }

        Double speed = number(
                values.get("speed"), 0, 9_007_199_254_740_991d
        );

        Double heading = number(values.get("heading"), 0, 360);

        if (heading != null && heading == 360) {
            heading = 0d;
        }

        Integer satellites = integer(
                values.get("satellites"), 0, 99
        );

        Integer fix = integer(values.get("fix"), 0, 4);

        String inputs = values.get("inputs");
        Boolean ignition = null;

        if (inputs != null) {
            if (!inputs.matches("[01]{8}")) {
                throw invalid("INVALID_INPUTS");
            }
            ignition = (Integer.parseInt(inputs, 2) & 1) == 1;
        }

        Boolean validFix = latitude == null
                ? null
                : fix != null && fix == 1;

        return new Telemetry(
                identity,
                recordedAt,
                latitude,
                longitude,
                speed,
                ignition,
                heading,
                satellites,
                validFix
        );
    }

    /**
     * Converte o campo decimal opcional e confere os limites aceitos para a grandeza.
     *
     * @param value valor a interpretar
     * @param min limite inclusivo do intervalo aceito
     * @param max limite inclusivo do intervalo aceito
     * @return valor convertido, ou nulo para campo ausente
     */
    private Double number(String value, double min, double max) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        if (!NUMBER.matcher(value).matches()) {
            throw invalid("INVALID_NUMBER");
        }

        double parsed = Double.parseDouble(value);

        if (!Double.isFinite(parsed)
                || parsed < min || parsed > max) {
            throw invalid("INVALID_NUMBER");
        }

        return parsed;
    }

    /**
     * Aplica os limites do campo e exige que o valor represente um inteiro.
     *
     * @param value valor a interpretar
     * @param min limite inclusivo do intervalo aceito
     * @param max limite inclusivo do intervalo aceito
     * @return valor convertido, ou nulo para campo ausente
     */
    private Integer integer(String value, int min, int max) {
        Double parsed = number(value, min, max);

        if (parsed == null) {
            return null;
        }

        if (parsed % 1 != 0) {
            throw invalid("INVALID_NUMBER");
        }

        return parsed.intValue();
    }

    /**
     * Cria o erro de validação com o código que identifica o campo ou formato rejeitado.
     *
     * @param code código que identifica o motivo da falha
     * @return exceção de formato usada para rejeitar o conteúdo
     */
    private IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
