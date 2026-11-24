package software.aporia.tracking.observability.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import software.aporia.tracking.gateway.TcpGateway;

@Component("trackingGatewayHealthIndicator")
@Profile("gateway")
public final class GatewayHealthIndicator
        implements HealthIndicator {

    private final TcpGateway gateway;

    public GatewayHealthIndicator(TcpGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public Health health() {

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