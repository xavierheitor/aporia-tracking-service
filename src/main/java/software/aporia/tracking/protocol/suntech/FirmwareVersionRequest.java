package software.aporia.tracking.protocol.suntech;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Constrói a consulta Suntech de versão de firmware e confere os bytes autorizados para a
 * identidade da sessão.
 */
public final class FirmwareVersionRequest {
    private FirmwareVersionRequest() {}

    /** Monta os bytes da consulta de firmware endereçada ao equipamento Suntech. */
    public static byte[] encode(String identity) {
        var payload = ("CMD;" + identity + ";03;04;1\r").getBytes(StandardCharsets.US_ASCII);
        new SuntechCommandValidator().validate(payload, identity);
        return payload;
    }

    /** Confere se protocolo, identidade e bytes correspondem exatamente à operação permitida. */
    public static boolean permits(String protocol, String identity, byte[] payload) {
        return "suntech".equals(protocol) && identity.matches("[0-9]{10}")
                && Arrays.equals(encode(identity), payload);
    }
}
