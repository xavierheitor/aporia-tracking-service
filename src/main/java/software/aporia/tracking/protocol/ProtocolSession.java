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
     */
    List<ProtocolFrame> receive(byte[] incoming);

    /** Produz a resposta de recusa prevista pelo protocolo, que pode ser uma sequência vazia. */
    default byte[] rejectLogin() { return new byte[0]; }

    /** Codifica a confirmação de login ou de dados exigida para o quadro recebido. */
    default byte[] acknowledge(ProtocolFrame frame) {
        return new byte[0];
    }
}
