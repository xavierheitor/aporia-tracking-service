package software.aporia.tracking.observability.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import software.aporia.tracking.gateway.TcpGateway;

/**
 * Traduz o estado do gateway para o endpoint de saúde no perfil {@code gateway}.
 *
 * <p>O indicador é registrado como {@code trackingGatewayHealthIndicator} e participa dos grupos {@code
 * liveness} e {@code readiness} definidos em {@code application-gateway.yml}. Uma falha capturada pela thread
 * do gateway ou um listener que não aceita conexões resultam em {@code DOWN}; caso contrário, o estado é
 * {@code UP} e inclui a porta local.</p>
 *
 * <p>A consulta apenas lê campos publicados pelo gateway e pode ser chamada concorrentemente pelo
 * endpoint.</p>
 */
@Component("trackingGatewayHealthIndicator")
@Profile("gateway")
public final class GatewayHealthIndicator
        implements HealthIndicator {

    /** Gateway cujo estado é consultado; a referência não muda após a construção. */
    private final TcpGateway gateway;

    /**
     * Guarda o gateway monitorado.
     *
     * @param gateway instância configurada para atender conexões TCP
     */
    public GatewayHealthIndicator(TcpGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * Avalia a disponibilidade do gateway.
     *
     * @return {@code UP} com a porta local ou {@code DOWN} com o motivo da indisponibilidade
     */
    @Override
    public Health health() {

        // Uma falha na thread de accept tem prioridade sobre a disponibilidade do listener.
        if (gateway.getFailure() != null) {
            return Health.down()
                    .withDetail("reason", "GATEWAY_FAILURE")
                    .build();
        }

        if (!gateway.isAcceptingConnections()) {
            return Health.down()
                    .withDetail("reason", "LISTENER_UNAVAILABLE")
                    .build();
        }

        return Health.up()
                .withDetail("port", gateway.getLocalPort())
                .build();
    }
}