package software.aporia.tracking.protocol.suntech;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Constrói a consulta Suntech de versão de firmware e confere se um payload é exatamente a consulta
 * autorizada para a identidade da sessão.
 *
 * <p>O quadro montado é {@code CMD;<identidade>;03;04;1\r}, em ASCII, com a identidade de dez dígitos exigida
 * pelo protocolo. A operação é fixa: não há parâmetro livre nesta classe.</p>
 *
 * <p>O dispatcher usa {@link #permits(String, String, byte[])} como política antes da escrita TCP. Construir
 * ou validar a consulta não confirma recebimento nem execução pelo equipamento.</p>
 */
public final class FirmwareVersionRequest {
    /**
     * Restringe a construção e a verificação do comando às operações estáticas da classe.
     */
    private FirmwareVersionRequest() {}

    /**
     * Monta os bytes da consulta de firmware endereçada ao equipamento Suntech.
     *
     * <p>O CR final é o delimitador de quadro. A validação usa a mesma identidade da montagem, portanto só
     * uma identidade fora da gramática, como uma sem dez dígitos, faz esta chamada falhar.</p>
     *
     * @param identity identidade declarada pelo rastreador no protocolo
     * @return consulta de firmware codificada em ASCII, terminada por CR
     * @throws IllegalArgumentException se a identidade não produzir um comando válido
     */
    public static byte[] encode(String identity) {
        // Os campos 03;04;1 identificam a consulta de versão; a identidade é intercalada sem escape.
        var payload = ("CMD;" + identity + ";03;04;1\r").getBytes(StandardCharsets.US_ASCII);
        // Reaproveitar o validador garante que esta montagem não emite gramática fora de CMD.
        new SuntechCommandValidator().validate(payload, identity);
        return payload;
    }

    /**
     * Confere se protocolo, identidade e bytes correspondem exatamente à operação permitida.
     *
     * <p>A comparação é feita contra {@link #encode(String)}, byte a byte, e não apenas contra a sintaxe:
     * outra operação CMD válida é recusada. O protocolo é comparado antes da identidade; uma identidade nula
     * falha com {@link NullPointerException} em vez de devolver {@code false}.</p>
     *
     * @param protocol identificador do protocolo do equipamento
     * @param identity identidade declarada pelo rastreador no protocolo
     * @param payload bytes do conteúdo da mensagem
     * @return verdadeiro se os bytes correspondem à consulta permitida para a identidade
     * @throws NullPointerException se o protocolo for {@code "suntech"} e a identidade for nula
     */
    public static boolean permits(String protocol, String identity, byte[] payload) {
        // Comparar todos os bytes restringe a operação, além da sintaxe do comando:
        // outra operação CMD válida não ganha permissão de envio por esse motivo.
        return "suntech".equals(protocol) && identity.matches("[0-9]{10}")
                && Arrays.equals(encode(identity), payload);
    }
}
