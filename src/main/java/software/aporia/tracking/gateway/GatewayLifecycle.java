package software.aporia.tracking.gateway;

import java.io.IOException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Adapta o {@link TcpGateway} ao ciclo de vida do contexto Spring. A partida automática abre o listener; a parada
 * solicita o fechamento das conexões e aguarda a liberação dos recursos pertencentes ao gateway.
 *
 * <p>
 * Na parada com callback, uma thread auxiliar faz a espera. O Spring só recebe a confirmação pelo callback após
 * {@link TcpGateway#awaitTermination()} retornar.
 * </p>
 */
public final class GatewayLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(GatewayLifecycle.class);

    private final TcpGateway gateway;

    /**
     * Guarda o gateway que será controlado pelo contexto.
     *
     * @param gateway instância configurada para atender conexões TCP
     * @throws NullPointerException se o gateway for nulo
     */
    public GatewayLifecycle(TcpGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway);
    }

    /**
     * Solicita a partida do gateway durante a inicialização do contexto.
     *
     * @throws IllegalStateException se o gateway não conseguir abrir seus recursos de E/S
     */
    @Override
    public void start() {
        try {
            gateway.start();
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Unable to start TCP gateway",
                    error);
        }
    }

    /**
     * Solicita a parada e aguarda a thread do gateway, sem timeout próprio.
     *
     * @throws IllegalStateException se a thread chamadora for interrompida durante a espera
     */
    @Override
    public void stop() {
        // Fechar o listener desbloqueia accept; fechar os clientes interrompe suas leituras.
        gateway.requestStop();

        try {
            gateway.awaitTermination();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Gateway shutdown interrupted",
                    error);
        }
    }

    /**
     * Solicita a parada e delega a espera a uma thread auxiliar. Se a espera for interrompida, registra a falha sem
     * confirmar uma terminação não observada.
     *
     * @param callback confirmação exigida pelo Spring após a terminação do gateway
     */
    @Override
    public void stop(Runnable callback) {
        gateway.requestStop();

        // A thread auxiliar evita bloquear o coordenador de encerramento do contexto.
        Thread.ofPlatform()
                .name("tracking-gateway-lifecycle-shutdown")
                .daemon(true)
                .start(() -> {
                    try {
                        gateway.awaitTermination();

                        // O callback confirma a liberação dos recursos, não apenas o pedido de parada.
                        callback.run();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();

                        log.error(
                                "Gateway shutdown interrupted",
                                error);
                    }
                });
    }

    /**
     * Consulta a thread do gateway, que pode continuar viva enquanto encerra seus recursos.
     *
     * @return verdadeiro enquanto a thread estiver viva, mesmo que o listener já tenha fechado
     */
    @Override
    public boolean isRunning() {
        return gateway.isRunning();
    }

    /**
     * Habilita a partida automática quando este bean estiver presente no contexto.
     *
     * @return sempre verdadeiro; o perfil controla a criação do bean
     */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * Define a ordem em relação aos outros componentes de ciclo de vida. Fases menores iniciam antes e encerram depois;
     * componentes na mesma fase não têm sua ordem relativa definida apenas por este número.
     *
     * @return fase 100, também usada pelo ciclo de vida do worker
     */
    @Override
    public int getPhase() {
        return 100;
    }
}
