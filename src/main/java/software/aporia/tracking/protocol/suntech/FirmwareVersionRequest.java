package software.aporia.tracking.protocol.suntech;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Constrói a consulta Suntech de versão de firmware e confere os bytes autorizados para a
 * identidade da sessão.
 */
public final class FirmwareVersionRequest {
    /**
     * Restringe a construção e a verificação do comando às operações estáticas da classe.
     */
    private FirmwareVersionRequest() {}

    /**
     * Monta os bytes da consulta de firmware endereçada ao equipamento Suntech.
     *
     * @param identity identidade declarada pelo rastreador no protocolo
     * @return consulta de firmware codificada em ASCII
     */
    public static byte[] encode(String identity) {
        var payload = ("CMD;" + identity + ";03;04;1\r").getBytes(StandardCharsets.US_ASCII);
        new SuntechCommandValidator().validate(payload, identity);
        return payload;
    }

    /**
     * Confere se protocolo, identidade e bytes correspondem exatamente à operação permitida.
     *
     * @param protocol identificador do protocolo do equipamento
     * @param identity identidade declarada pelo rastreador no protocolo
     * @param payload bytes do conteúdo da mensagem
     * @return verdadeiro se os bytes correspondem à consulta permitida para a identidade
     */
    public static boolean permits(String protocol, String identity, byte[] payload) {
        return "suntech".equals(protocol) && identity.matches("[0-9]{10}")
                && Arrays.equals(encode(identity), payload);
    }
}
