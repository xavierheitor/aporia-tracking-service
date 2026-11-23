package software.aporia.tracking.protocol;

import java.util.List;

/**
 * Mantém o estado de enquadramento de uma conexão, inclusive os bytes restantes entre duas
 * leituras TCP.
 */
public interface ProtocolSession {

    /**
     * Acumula os bytes de uma leitura e devolve os quadros completos, na ordem em que chegaram.
     * Um fragmento pode produzir uma lista vazia.
     *
     * @param incoming bytes disponíveis nesta leitura TCP, possivelmente um fragmento
     * @return quadros completos nesta leitura, em ordem de recepção
     */
    List<ProtocolFrame> receive(byte[] incoming);

    /**
     * Produz a resposta de recusa prevista pelo protocolo, que pode ser uma sequência vazia.
     *
     * @return bytes de resposta previstos pelo protocolo
     */
    default byte[] rejectLogin() { return new byte[0]; }

    /**
     * Codifica a confirmação de login ou de dados exigida para o quadro recebido.
     *
     * @param frame quadro recebido para interpretação ou ingestão
     * @return bytes de resposta previstos pelo protocolo
     */
    default byte[] acknowledge(ProtocolFrame frame) {
        return new byte[0];
    }
}
