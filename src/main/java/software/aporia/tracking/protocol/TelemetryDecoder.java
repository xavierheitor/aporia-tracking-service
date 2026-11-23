package software.aporia.tracking.protocol;

/**
 * Define como transformar os bytes persistidos pelo gateway em leituras e respostas
 * interpretáveis pelo worker.
 */
public interface TelemetryDecoder {

    /** Identifica o protocolo de origem dos pacotes que este decodificador interpreta. */
    String protocolId();

    /** Identifica a implementação usada para interpretar o formato persistido. */
    String decoderVersion();

    /**
     * Interpreta um pacote completo usando a identidade cadastrada como contexto e preserva a
     * ordem das leituras.
     */
    DecodedPacket decodePacket(byte[] frame, String registeredIdentity);
}
