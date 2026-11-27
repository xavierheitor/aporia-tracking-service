package software.aporia.tracking.protocol.suntech;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;

import software.aporia.tracking.core.Telemetry;
import software.aporia.tracking.protocol.DecodedPacket;
import software.aporia.tracking.protocol.TelemetryDecoder;

/**
 * Interpreta mensagens Suntech, incluindo posições com mapa de campos, heartbeats e respostas com telemetria
 * embutida.
 *
 * <p>{@link #decodePacket(byte[], String)} determina os conteúdos presentes e monta o resultado do worker.
 * {@link #decode(byte[])} interpreta uma leitura completa; {@code decodePosition} usa o mapa para localizar
 * apenas os campos transmitidos. A identidade sempre vem do próprio quadro, não do cadastro; o worker é quem
 * compara as duas.</p>
 *
 * <p>A classe não guarda o buffer de recepção. O {@link SuntechProtocol} interno é usado apenas para extrair
 * a identidade, sem acumular bytes entre chamadas.</p>
 */
public class SuntechDecoder implements TelemetryDecoder {

    /**
     * Identifica a família dos quadros interpretados pelo decodificador.
     *
     * @return identificador do protocolo desta implementação
     */
    @Override public @NonNull String protocolId() { return "suntech"; }

    /**
     * Identifica a versão do formato gravada com os pacotes Suntech.
     *
     * <p>Deve coincidir com a versão informada por {@link SuntechAdapter#decoderVersion()}, pois o worker
     * seleciona o decodificador por esse texto.</p>
     *
     * @return versão usada para interpretar os bytes persistidos
     */
    @Override
    public String decoderVersion() {
        return "suntech-ascii-v3";
    }

    /**
     * Nomes dos campos na ordem dos bits do mapa de presença.
     *
     * <p>Cobre os bits 0 a 21; o bit 22 não é aceito e o bit 23 anuncia o mapa estendido, cujos valores não
     * viram propriedades de {@link Telemetry}.</p>
     */
    private static final String[] FIELDS = {
        "map", "model", "firmware", "realtime",
        "date", "time", "cell", "mcc",
        "mnc", "lac", "signal", "lat",
        "lon", "speed", "heading", "satellites",
        "fix", "inputs", "outputs", "mode",
        "reason", "sequence"
    };

    /** Formato decimal aceito antes da conversão: sinal opcional e ponto, sem expoente. */
    private static final Pattern NUMBER = Pattern.compile(
            "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)"
    );

    /** Enquadrador usado apenas para extrair a identidade; não acumula bytes de recepção aqui. */
    private final SuntechProtocol protocol = new SuntechProtocol();

    /**
     * Combina o plano de roteamento com a leitura de telemetria, preservando o tipo de resposta
     * em mensagens compostas.
     *
     * <p>Heartbeat gera uma leitura sem posição; resposta sem posição gera lista vazia. O campo {@code
     * responseType} é apenas o cabeçalho ({@code RES} ou {@code RPR}), sem identificar qual comando originou
     * a resposta. {@code registeredIdentity} não é usado: a identidade é lida do quadro e a comparação com o
     * cadastro fica a cargo do worker.</p>
     *
     * @param frame quadro recebido para interpretação ou ingestão
     * @param registeredIdentity identidade de referência do equipamento; não usada aqui
     * @return identidade, leituras e eventual resposta presentes no pacote
     * @throws IllegalArgumentException se o quadro, o cabeçalho ou os campos forem inválidos
     */
    @Override
    public DecodedPacket decodePacket(byte[] frame, String registeredIdentity) {
        // Suntech traz a identidade no próprio quadro; o worker compara o resultado
        // com o cadastro. Teltonika obtém essa identidade do contexto de login.
        String identity = protocol.identify(frame);
        var plan = new SuntechMessageRouter().route(frame);
        // Heartbeat gera uma leitura sem posição; uma resposta simples gera lista vazia.
        // Uma resposta com posição pode produzir telemetria e responseType ao mesmo tempo.
        var readings = plan.processTelemetry() || plan.processHeartbeat()
                ? java.util.List.of(decode(frame)) : java.util.List.<Telemetry>of();
        String response = plan.processCommandResponse() ? new String(frame, 0, 3, StandardCharsets.US_ASCII) : null;
        // O tipo registrado é o cabeçalho, não o comando: não há correlação automática aqui.
        return new DecodedPacket(identity, readings, response);
    }

    /**
     * Extrai a telemetria de uma mensagem de posição ou presença, inclusive de uma posição
     * embutida em resposta.
     *
     * <p>O último byte deve ser o CR; o texto é dividido preservando campos vazios. Uma resposta {@code RES}
     * sem posição embutida não tem telemetria e é rejeitada com {@link UnsupportedOperationException}; {@link
     * #decodePacket(byte[], String)} evita chamar este método nesse caso.</p>
     *
     * @param frame quadro recebido para interpretação ou ingestão
     * @return telemetria interpretada a partir dos campos do equipamento
     * @throws IllegalArgumentException se o cabeçalho ou os campos forem inválidos
     * @throws UnsupportedOperationException se for uma resposta sem posição embutida
     */
    public Telemetry decode(byte[] frame) {

        String identity = protocol.identify(frame);

        // identify já validou o CR final; removê-lo deixa os campos sem o terminador no último valor.
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

                // ALV não traz mapa: a leitura registra apenas a presença do equipamento.
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

                    // Retira apenas RES para reutilizar a interpretação de STT/ASTT.
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

            // RPR, aceito no envelope, não tem leitura e cai aqui quando decode é chamado direto.
            default ->
                throw invalid("UNSUPPORTED_MESSAGE");
        };
    }

    /**
     * Percorre o mapa de presença para associar cada campo à sua posição no texto e validar os
     * valores da leitura.
     *
     * <p>O mapa precisa ter o bit 0 ligado e não pode usar bits fora de {@code 0xBFFFFF}. Cada bit ligado
     * consome exatamente um campo, na ordem de {@link #FIELDS}. O bit 23 consome um mapa estendido de oito
     * dígitos hexadecimais e os campos indicados por ele, que são descartados. Um {@code ;} imediatamente
     * antes do CR é aceito como campo vazio final.</p>
     *
     * @param identity identidade declarada pelo rastreador no protocolo
     * @param parts campos da mensagem separados por ponto e vírgula
     * @return telemetria interpretada a partir dos campos do equipamento
     * @throws IllegalArgumentException se o mapa, a contagem ou qualquer valor validado for
     *         inválido
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

        // & testa bits: o bit 0 exige o próprio mapa; ~ inverte a máscara para
        // detectar bits fora do conjunto que esta implementação aceita.
        if ((map & 1) == 0 || (map & ~0xBFFFFF) != 0) {
            throw invalid("UNSUPPORTED_MAP");
        }

        Map<String, String> values = new HashMap<>();
        int index = 2;

        // Cada bit presente consome um campo. Um bit ausente não ocupa posição no texto recebido.
        for (int bit = 0; bit < FIELDS.length; bit++) {
            // 1 << bit cria a máscara de uma posição: 1, 2, 4, 8, ...
            if ((map & (1 << bit)) != 0) {
                if (index >= parts.length) {
                    throw invalid("INVALID_FIELD_COUNT");
                }
                values.put(FIELDS[bit], parts[index++]);
            }
        }

        // O bit 23 anuncia outro mapa. Cada bit ligado nele ocupa mais um campo;
        // esses valores são contados, mas ainda não viram propriedades de Telemetry.
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
                // Monta um instante UTC; o sufixo Z evita depender do fuso da JVM.
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

        // 360 graus é equivalente a 0; normalizar mantém o rumo no intervalo [0, 360).
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
            // Nesta implementação, a ignição é o bit menos significativo das entradas digitais.
            ignition = (Integer.parseInt(inputs, 2) & 1) == 1;
        }

        // Sem coordenadas não há posição a validar. Com coordenadas, só fix=1 é posição válida;
        // fix ausente ou outro código aceito resulta em false.
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
     * @throws IllegalArgumentException se o valor não tiver forma decimal ou estiver fora do
     *         intervalo
     */
    private Double number(String value, double min, double max) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        // A regex restringe a forma decimal; a finitude e a faixa são conferidas depois.
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
     * @throws IllegalArgumentException se o valor não for um inteiro dentro do intervalo
     */
    private Integer integer(String value, int min, int max) {
        Double parsed = number(value, min, max);

        if (parsed == null) {
            return null;
        }

        // number já aplicou a faixa; aqui só falta recusar uma parte fracionária.
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
