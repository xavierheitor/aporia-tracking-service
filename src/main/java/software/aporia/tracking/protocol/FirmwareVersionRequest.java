package software.aporia.tracking.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class FirmwareVersionRequest {
    private FirmwareVersionRequest() {}

    public static byte[] encode(String identity) {
        var payload = ("CMD;" + identity + ";03;04;1\r").getBytes(StandardCharsets.US_ASCII);
        new SuntechCommandValidator().validate(payload, identity);
        return payload;
    }

    public static boolean permits(String protocol, String identity, byte[] payload) {
        return "suntech".equals(protocol) && identity.matches("[0-9]{10}")
                && Arrays.equals(encode(identity), payload);
    }
}
