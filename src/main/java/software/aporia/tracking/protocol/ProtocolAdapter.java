package software.aporia.tracking.protocol;

/**
 * Define a detecção e a abertura de sessões de um protocolo e a descrição de equipamentos a
 * partir de quadros reconhecidos.
 */
public interface ProtocolAdapter {

    /** Identificador usado no cadastro do dispositivo e na persistência do pacote. */
    String protocolId();

    /** Versão gravada com os bytes brutos para orientar o processamento posterior. */
    String decoderVersion();

    /**
     * Indica se o prefixo pode iniciar uma conexão deste protocolo. O restante do quadro ainda
     * precisa ser validado.
     */
    boolean acceptsFirstByte(int first);

    /** Extrai metadados de descoberta a partir de um quadro recebido pela sessão do adaptador. */
    DeviceDescription describe(ProtocolFrame frame);

    /** Cria o estado de enquadramento exclusivo de uma conexão TCP. */
    ProtocolSession newSession();
}