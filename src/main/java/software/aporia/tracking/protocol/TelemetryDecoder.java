package software.aporia.tracking.protocol;

import org.jspecify.annotations.NonNull;

/**
 * Define como transformar os bytes persistidos pelo gateway em leituras e respostas
 * interpretáveis pelo worker.
 *
 * <p>Recebe um pacote já enquadrado, não fragmentos TCP. A versão identifica a interpretação dos bytes
 * persistidos e permite ao worker selecionar o decoder correto mesmo quando o processamento acontece depois
 * da recepção. O registro compartilha o decodificador entre pacotes, então a implementação não deve guardar
 * estado de um pacote entre chamadas.</p>
 */
public interface TelemetryDecoder {

    /**
     * Identifica o protocolo de origem dos pacotes que este decodificador interpreta.
     *
     * @return identificador do protocolo desta implementação
     */
    @NonNull String protocolId();

    /**
     * Identifica a implementação usada para interpretar o formato persistido.
     *
     * @return versão usada para interpretar os bytes persistidos
     */
    String decoderVersion();

    /**
     * Interpreta um pacote completo usando a identidade cadastrada como contexto e preserva a
     * ordem das leituras.
     *
     * @param frame quadro recebido para interpretação ou ingestão
     * @param registeredIdentity identidade de referência do equipamento
     * @return identidade, leituras e eventual resposta presentes no pacote
     * @throws IllegalArgumentException se o pacote não seguir o formato desta versão
     */
    DecodedPacket decodePacket(byte[] frame, String registeredIdentity);
}
