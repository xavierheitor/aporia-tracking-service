package software.aporia.tracking.protocol;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import software.aporia.tracking.core.Telemetry;

public final class TeltonikaAvl implements TelemetryDecoder {
    static final int MAX_PACKET = 1280;

    @Override public String protocolId() { return "teltonika"; }

    @Override public String decoderVersion() { return "teltonika-avl-v1"; }

    @Override
    public DecodedPacket decodePacket(byte[] packet, String identity) {
        try {
            ByteBuffer data = ByteBuffer.wrap(packet);
            if (packet.length > MAX_PACKET || data.getInt() != 0 || data.getInt() != packet.length - 12) {
                throw invalid();
            }
            int end = packet.length - 4;
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
        field(data, extended); // event IO ID
        int remaining = field(data, extended);
        Boolean ignition = null;
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

    private static int field(ByteBuffer data, boolean extended) {
        return extended ? Short.toUnsignedInt(data.getShort()) : Byte.toUnsignedInt(data.get());
    }

    private static void skip(ByteBuffer data, int length) {
        if (length > data.remaining()) throw invalid();
        data.position(data.position() + length);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_TELTONIKA_AVL");
    }
}
