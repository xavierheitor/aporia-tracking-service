package software.aporia.tracking.protocol;

/**
 * Define a detecção e a abertura de sessões de um protocolo e a descrição de equipamentos a
 * partir de quadros reconhecidos.
 */
public interface ProtocolAdapter {

    /**
     * Identificador usado no cadastro do dispositivo e na persistência do pacote.
     *
     * @return identificador do protocolo desta implementação
     */
    String protocolId();

    /**
     * Versão gravada com os bytes brutos para orientar o processamento posterior.
     *
     * @return versão usada para interpretar os bytes persistidos
     */
    String decoderVersion();

    /**
     * Indica se o prefixo pode iniciar uma conexão deste protocolo. O restante do quadro ainda
     * precisa ser validado.
     *
     * @param first primeiro byte da conexão, interpretado sem sinal
     * @return verdadeiro quando o prefixo é compatível com o adaptador
     */
    boolean acceptsFirstByte(int first);

    /**
     * Extrai metadados de descoberta a partir de um quadro recebido pela sessão do adaptador.
     *
     * @param frame quadro recebido para interpretação ou ingestão
     * @return metadados que podem compor a pendência de autorização
     */
    DeviceDescription describe(ProtocolFrame frame);

    /**
     * Cria o estado de enquadramento exclusivo de uma conexão TCP.
     *
     * @return sessão de protocolo com estado independente
     */
    ProtocolSession newSession();
}