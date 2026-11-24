package software.aporia.tracking.protocol;

import java.util.List;

/**
 * Mantém o estado de enquadramento de uma conexão, inclusive os bytes restantes entre duas
 * leituras TCP.
 *
 * <p>Enquadrar significa encontrar os limites das mensagens dentro do fluxo de
 * bytes. Uma sessão pertence a uma conexão e não deve ser compartilhada entre
 * sockets. Os métodos de ACK apenas produzem bytes; não escrevem na rede.</p>
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
