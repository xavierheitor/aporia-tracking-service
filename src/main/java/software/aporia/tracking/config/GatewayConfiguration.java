package software.aporia.tracking.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import software.aporia.tracking.gateway.GatewayLifecycle;
import software.aporia.tracking.gateway.TcpGateway;

/**
 * Monta os componentes TCP quando o perfil {@code gateway} está ativo. O Spring fornece o pool e resolve as
 * propriedades antes de criar o gateway. A criação dos beans não abre o listener: a partida é feita por
 * {@link GatewayLifecycle}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("gateway")
public class GatewayConfiguration {
    /**
     * Cria o gateway com a configuração resolvida pelo Spring, sem iniciar o atendimento.
     *
     * @param dataSource pool gerenciado pelo contexto da aplicação
     * @param bindAddress endereço local em que o listener deve escutar
     * @param port porta TCP; zero permite ao sistema operacional escolher uma porta livre
     * @param protocolId protocolo fixo ou {@code auto} para detecção pelo primeiro byte
     * @param dispatchOwnerFile arquivo persistente do emissor; vazio desabilita o polling de comandos
     * @return gateway cujo ciclo de vida será controlado pelo Spring
     */
    @Bean
    public TcpGateway tcpGateway(
            DataSource dataSource,
            @Value("${tracking.gateway.bind}") String bindAddress,
            @Value("${tracking.gateway.port}") int port,
            @Value("${tracking.gateway.protocol}") String protocolId,
            @Value("${tracking.gateway.dispatch-owner-file}") String dispatchOwnerFile) {
        // O gateway recebe o pool emprestado; o contexto continua responsável por fechá-lo.
        return new TcpGateway(
                dataSource,
                bindAddress,
                port,
                protocolId,
                dispatchOwnerFile);
    }

    /**
     * Conecta a partida e a parada do gateway ao ciclo de vida do contexto.
     *
     * @param tcpGateway instância criada por {@link #tcpGateway(DataSource, String, int, String, String)}
     * @return componente que inicia e encerra o atendimento TCP
     */
    @Bean
    public GatewayLifecycle gatewayLifecycle(
            TcpGateway tcpGateway) {
        return new GatewayLifecycle(tcpGateway);
    }
}
