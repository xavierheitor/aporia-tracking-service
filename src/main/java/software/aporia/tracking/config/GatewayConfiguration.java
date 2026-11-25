package software.aporia.tracking.config;

import javax.sql.DataSource;

import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;

import software.aporia.tracking.gateway.GatewayLifecycle;
import software.aporia.tracking.gateway.TcpGateway;
import software.aporia.tracking.observability.metrics.CommandMetrics;
import software.aporia.tracking.observability.metrics.GatewayMetrics;

/**
 * Monta os componentes TCP quando o perfil {@code gateway} está ativo. O Spring fornece o pool, as métricas e resolve
 * as propriedades antes de criar o gateway.
 *
 * A criação dos beans não abre o listener: a partida é feita por {@link GatewayLifecycle}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("gateway")
public class GatewayConfiguration {

    /**
     * Cria o gateway com as dependências e configurações resolvidas pelo Spring, sem iniciar o atendimento.
     *
     * @param dataSource        pool gerenciado pelo contexto
     * @param metrics           métricas de conexões e ingestão
     * @param commandMetrics    métricas de despacho e recuperação
     * @param bindAddress       endereço local do listener
     * @param port              porta TCP de escuta
     * @param protocolId        protocolo fixo ou {@code auto}
     * @param dispatchOwnerFile arquivo persistente do emissor; vazio desabilita o polling
     * @return gateway controlado pelo ciclo de vida do Spring
     */
    @Bean
    public TcpGateway tcpGateway(
            DataSource dataSource,
            GatewayMetrics metrics,
            CommandMetrics commandMetrics,
            @Value("${tracking.gateway.bind}") String bindAddress,
            @Value("${tracking.gateway.port}") int port,
            @Value("${tracking.gateway.protocol}") String protocolId,
            @Value("${tracking.gateway.dispatch-owner-file}") String dispatchOwnerFile) {

        return new TcpGateway(
                dataSource,
                bindAddress,
                port,
                protocolId,
                dispatchOwnerFile,
                metrics,
                commandMetrics);
    }

    /**
     * Conecta a partida e a parada do gateway ao ciclo de vida do contexto.
     *
     * @param tcpGateway gateway gerenciado pelo Spring
     * @return componente responsável pelo ciclo de vida TCP
     */
    @Bean
    public GatewayLifecycle gatewayLifecycle(
            TcpGateway tcpGateway) {

        return new GatewayLifecycle(tcpGateway);
    }

    /**
     * Registra os indicadores de estado atual do gateway.
     *
     * @param gateway instância monitorada
     * @return componente de registro das métricas
     */
    @Bean
    @NullMarked
    public MeterBinder gatewayGauges(TcpGateway gateway) {

        return registry -> {

            Gauge.builder(
                    "tracking.tcp.connections.active",
                    gateway,
                    TcpGateway::getActiveConnectionCount)
                    .description("Currently active TCP connections")
                    .register(registry);

            Gauge.builder(
                    "tracking.devices.connected",
                    gateway,
                    TcpGateway::getConnectedDeviceCount)
                    .description("Locally registered device sessions")
                    .register(registry);
        };
    }
}
