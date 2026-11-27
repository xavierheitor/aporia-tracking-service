package software.aporia.tracking.protocol.teltonika;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;

import org.jspecify.annotations.NonNull;

import software.aporia.tracking.core.Telemetry;
import software.aporia.tracking.protocol.DecodedPacket;
import software.aporia.tracking.protocol.TelemetryDecoder;

/**
 * Valida lotes AVL Codec 8 e 8E e extrai suas leituras GPS na ordem dos registros.
 *
 * <p>{@link #decodePacket(byte[], String)} confere o envelope, o CRC e a contagem.
 * Cada chamada a {@code readRecord()} avança o mesmo buffer pelos campos GPS e
 * pelas entradas e saídas (I/O). Os codecs diferem na largura de identificadores
 * e contagens; o Codec 8E também admite valores de tamanho variável.</p>
 *
 * <p>O decodificador não guarda estado entre chamadas: cada invocação cria seu próprio buffer sobre o array
 * recebido, então a instância pode ser compartilhada entre threads. Lotes truncados ou inconsistentes viram
 * {@code IllegalArgumentException} com o código {@code INVALID_TELTONIKA_AVL}.</p>
 */
public final class TeltonikaAvl implements TelemetryDecoder {
    /** Limite do pacote AVL em bytes, incluindo cabeçalho e CRC. */
    static final int MAX_PACKET = 1280;

    /**
     * Identifica a família de protocolo dos lotes AVL suportados.
     *
     * @return identificador do protocolo desta implementação
     */
    @Override public @NonNull String protocolId() { return "teltonika"; }

    /**
     * Informa a versão de decodificação usada na persistência do pacote.
     *
     * @return versão usada para interpretar os bytes persistidos
     */
    @Override public String decoderVersion() { return "teltonika-avl-v1"; }

    /**
     * Valida comprimento, CRC, codec e contagens antes de devolver as leituras na ordem do lote.
     *
     * @param packet pacote bruto a processar
     * @param identity identidade declarada pelo rastreador no protocolo
     * @return identidade, leituras e eventual resposta presentes no pacote
     * @throws IllegalArgumentException se tamanho, CRC, codec ou estrutura do lote forem inválidos
     */
    @Override
    public DecodedPacket decodePacket(byte[] packet, String identity) {
        try {
            ByteBuffer data = ByteBuffer.wrap(packet);
            if (packet.length > MAX_PACKET || data.getInt() != 0 || data.getInt() != packet.length - 12) {
                throw invalid();
            }
            // O primeiro int deve ser zero e o segundo deve ser o tamanho do corpo; o pacote
            // tem oito bytes de cabeçalho e quatro bytes finais de CRC.
            int end = packet.length - 4;
            // O CRC verifica a integridade do corpo antes de interpretar seus campos.
            if (data.getInt(end) != crc16(packet, 8, end)) throw invalid();
            // Esconde o CRC das leituras seguintes: o parser só pode consumir o corpo.
            data.limit(end);
            int codec = Byte.toUnsignedInt(data.get());
            if (codec != 8 && codec != 0x8e) throw invalid();
            boolean extended = codec == 0x8e;
            int count = Byte.toUnsignedInt(data.get());
            if (count == 0) throw invalid();
            var readings = new ArrayList<Telemetry>(count);
            for (int i = 0; i < count; i++) readings.add(readRecord(data, extended, identity));
            // A quantidade se repete no fim. Divergência ou sobra de bytes invalida o lote.
            if (Byte.toUnsignedInt(data.get()) != count || data.hasRemaining()) throw invalid();
            return new DecodedPacket(identity, readings, null);
        } catch (BufferUnderflowException | IndexOutOfBoundsException error) {
            throw invalid();
        }
    }

    /**
     * Calcula o CRC-16/IBM do corpo, sem incluir cabeçalho nem os quatro bytes
     * finais que armazenam o CRC recebido.
     *
     * @param packet bytes originais do lote
     * @param start primeiro índice incluído no cálculo
     * @param end índice final exclusivo
     * @return valor de integridade calculado
     */
    private static int crc16(byte[] packet, int start, int end) {
        int crc = 0;
        for (int i = start; i < end; i++) {
            crc ^= Byte.toUnsignedInt(packet[i]);
            for (int bit = 0; bit < 8; bit++) {
                // Processa um bit por vez; 0xA001 é o polinômio desta variante do CRC.
                crc = (crc & 1) == 0 ? crc >>> 1 : (crc >>> 1) ^ 0xA001;
            }
        }
        return crc;
    }

    /**
     * Lê um registro GPS e percorre os grupos de entradas e saídas, interpretando a ignição
     * quando disponível.
     *
     * <p>O timestamp vira um {@link Instant} UTC. A leitura resultante usa {@code satellites > 0} como
     * indicação de fix válido e reduz 360° de rumo a 0°.</p>
     *
     * @param data buffer posicionado no próximo campo binário
     * @param extended indica uso das larguras do Codec 8 Extended
     * @param identity identidade declarada pelo rastreador no protocolo
     * @return telemetria interpretada a partir dos campos do equipamento
     * @throws IllegalArgumentException se os campos estiverem fora das faixas aceitas ou se as
     * contagens de E/S não fecharem
     */
    private static Telemetry readRecord(ByteBuffer data, boolean extended, String identity) {
        long millis = data.getLong();
        if (millis < 0 || millis > 253402300799999L || Byte.toUnsignedInt(data.get()) > 2) throw invalid();
        // Coordenadas são inteiros com sete casas decimais implícitas.
        double lon = data.getInt() / 1e7;
        double lat = data.getInt() / 1e7;
        data.getShort(); // altitude
        int heading = Short.toUnsignedInt(data.getShort());
        int satellites = Byte.toUnsignedInt(data.get());
        int speed = Short.toUnsignedInt(data.getShort());
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || heading > 360 || satellites > 99) throw invalid();
        field(data, extended); // Identificador da entrada ou saída que originou o evento.
        int remaining = field(data, extended);
        Boolean ignition = null;
        // O total de E/S vem antes dos grupos de 1, 2, 4 e 8 bytes. A contagem restante
        // precisa fechar com o fim dos grupos ou com o grupo variável do Codec 8E.
        for (int width = 1; width <= 8; width *= 2) {
            int count = field(data, extended);
            remaining -= count;
            if (remaining < 0) throw invalid();
            for (int i = 0; i < count; i++) {
                int id = field(data, extended);
                // Somente o I/O 239 de um byte é interpretado como ignição aqui.
                // Os demais valores continuam preservados no pacote bruto.
                if (id == 239 && width == 1) {
                    int value = Byte.toUnsignedInt(data.get());
                    if (value <= 1) ignition = value == 1;
                } else {
                    skip(data, width);
                }
            }
        }
        if (extended) {
            // No grupo variável do Codec 8E, cada item informa seu próprio comprimento.
            int count = field(data, true);
            if (remaining != count) throw invalid();
            for (int i = 0; i < count; i++) {
                field(data, true);
                skip(data, field(data, true));
            }
        } else if (remaining != 0) throw invalid();
        return new Telemetry(identity, Instant.ofEpochMilli(millis), lat, lon, (double) speed,
                ignition, (double) (heading % 360), satellites, satellites > 0);
    }

    /**
     * Consome um campo sem sinal usando a largura definida pelo codec.
     *
     * @param data buffer posicionado no próximo campo binário
     * @param extended indica uso das larguras do Codec 8 Extended
     * @return valor inteiro sem sinal consumido do buffer
     */
    private static int field(ByteBuffer data, boolean extended) {
        // O Codec 8 usa um byte; o 8E usa dois. Ambos representam valores sem sinal.
        return extended ? Short.toUnsignedInt(data.getShort()) : Byte.toUnsignedInt(data.get());
    }

    /**
     * Avança sobre um valor de E/S após conferir se ele cabe nos bytes restantes.
     *
     * @param data buffer posicionado no próximo campo binário
     * @param length quantidade de bytes a consumir
     */
    private static void skip(ByteBuffer data, int length) {
        // Confere o restante antes de avançar para não ler além do corpo do lote.
        if (length > data.remaining()) throw invalid();
        data.position(data.position() + length);
    }

    /**
     * Padroniza a rejeição de lotes AVL truncados ou estruturalmente inválidos.
     *
     * @return exceção de formato usada para rejeitar o conteúdo
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_TELTONIKA_AVL");
    }
}
