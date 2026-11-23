package software.aporia.tracking.protocol.teltonika;

import software.aporia.tracking.protocol.DecodedPacket;
import software.aporia.tracking.protocol.TelemetryDecoder;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import software.aporia.tracking.core.Telemetry;

/** Valida lotes AVL Codec 8 e 8E e extrai suas leituras GPS na ordem dos registros. */
public final class TeltonikaAvl implements TelemetryDecoder {
    /** Limite do pacote AVL em bytes, incluindo cabeçalho e CRC. */
    static final int MAX_PACKET = 1280;

    /**
     * Identifica a família de protocolo dos lotes AVL suportados.
     *
     * @return identificador do protocolo desta implementação
     */
    @Override public String protocolId() { return "teltonika"; }

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
            int end = packet.length - 4;
            // O CRC-16/IBM cobre do identificador do codec até a repetição final da quantidade de registros.
            int crc = 0;
            for (int i = 8; i < end; i++) {
                crc ^= Byte.toUnsignedInt(packet[i]);
                for (int bit = 0; bit < 8; bit++) {
                    crc = (crc & 1) == 0 ? crc >>> 1 : (crc >>> 1) ^ 0xA001;
                }
            }
            if (data.getInt(end) != crc) throw invalid();
            data.limit(end);
            int codec = Byte.toUnsignedInt(data.get());
            if (codec != 8 && codec != 0x8e) throw invalid();
            boolean extended = codec == 0x8e;
            int count = Byte.toUnsignedInt(data.get());
            if (count == 0) throw invalid();
            var readings = new ArrayList<Telemetry>(count);
            for (int i = 0; i < count; i++) readings.add(readRecord(data, extended, identity));
            if (Byte.toUnsignedInt(data.get()) != count || data.hasRemaining()) throw invalid();
            return new DecodedPacket(identity, readings, null);
        } catch (BufferUnderflowException | IndexOutOfBoundsException error) {
            throw invalid();
        }
    }

    /**
     * Lê um registro GPS e percorre os grupos de entradas e saídas, interpretando a ignição
     * quando disponível.
     *
     * @param data buffer posicionado no próximo campo binário
     * @param extended indica uso das larguras do Codec 8 Extended
     * @param identity identidade declarada pelo rastreador no protocolo
     * @return telemetria interpretada a partir dos campos do equipamento
     */
    private static Telemetry readRecord(ByteBuffer data, boolean extended, String identity) {
        long millis = data.getLong();
        if (millis < 0 || millis > 253402300799999L || Byte.toUnsignedInt(data.get()) > 2) throw invalid();
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
        // Os elementos de E/S vêm agrupados pela largura do valor. A contagem restante confere o total declarado.
        for (int width = 1; width <= 8; width *= 2) {
            int count = field(data, extended);
            remaining -= count;
            if (remaining < 0) throw invalid();
            for (int i = 0; i < count; i++) {
                int id = field(data, extended);
                if (id == 239 && width == 1) {
                    int value = Byte.toUnsignedInt(data.get());
                    if (value <= 1) ignition = value == 1;
                } else {
                    skip(data, width);
                }
            }
        }
        if (extended) {
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
        return extended ? Short.toUnsignedInt(data.getShort()) : Byte.toUnsignedInt(data.get());
    }

    /**
     * Avança sobre um valor de E/S após conferir se ele cabe nos bytes restantes.
     *
     * @param data buffer posicionado no próximo campo binário
     * @param length quantidade de bytes a consumir
     */
    private static void skip(ByteBuffer data, int length) {
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
