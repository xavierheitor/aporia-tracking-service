package software.aporia.tracking.protocol;

import java.util.ArrayList;
import java.util.HexFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TeltonikaAdapterTest {
    // TCP examples published in Teltonika Data Sending Protocols.
    @ParameterizedTest
    @ValueSource(strings = {
        "000000000000003608010000016B40D8EA30010000000000000000000000000000000105021503010101425E0F01F10000601A014E0000000000000000010000C7CF",
        "000000000000004A8E010000016B412CEE000100000000000000000000000000000000010005000100010100010011001D00010010015E2C880002000B000000003544C87A000E000000001DD7E06A00000100002994"
    })
    void fragmentedLoginAndAvl(String wire) {
        var hex = HexFormat.of();
        var session = new TeltonikaAdapter().newSession();
        var frames = new ArrayList<ProtocolFrame>();
        for (byte b : hex.parseHex("000F333536333037303432343431303133FFFF" + wire)) {
            frames.addAll(session.receive(new byte[]{b}));
        }
        assertEquals(2, frames.size());
        assertArrayEquals(new byte[]{1}, session.acknowledge(frames.getFirst()));
        var packet = frames.getLast();
        assertEquals("356307042441013", packet.deviceIdentity());
        assertArrayEquals(new byte[]{0,0,0,1}, session.acknowledge(packet));
        assertFalse(new TeltonikaAvl().decodePacket(packet.payload(), packet.deviceIdentity()).readings().getFirst().hasPosition());
        byte[] corrupt = packet.payload();
        corrupt[corrupt.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> session.receive(corrupt));
    }
}
