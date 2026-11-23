package software.aporia.tracking.protocol.suntech;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** Verifica os caminhos de processamento ativados para cada combinação de capacidades. */
class SuntechMessageRouterTest {

    /** Roteador usado para verificar o plano de processamento. */
    private final SuntechMessageRouter router
            = new SuntechMessageRouter();

    /**
     * Transforma a mensagem de exemplo no plano usado pelo worker.
     *
     * @param message mensagem do protocolo usada no cenário
     * @return plano com os processamentos aplicáveis à mensagem
     */
    private MessageProcessingPlan route(String message) {
        return router.route(
                message.getBytes(StandardCharsets.US_ASCII)
        );
    }

    /**
     * Encaminha uma posição para o processamento de telemetria.
     */
    @Test
    void shouldRouteTelemetry() {

        var plan = route("STT;1234567890;...");

        assertTrue(plan.processTelemetry());
        assertFalse(plan.processCommandResponse());
    }

    /**
     * Ativa o registro da resposta a comando.
     */
    @Test
    void shouldRouteCommandResponse() {

        var plan = route(
                "RES;1234567890;03;04;01;STB.201.1.1.11"
        );

        assertFalse(plan.processTelemetry());
        assertTrue(plan.processCommandResponse());
    }

    /**
     * Ativa os dois caminhos quando a resposta inclui posição.
     */
    @Test
    void shouldRouteTelemetryAndCommandResponse() {

        var plan = route(
                "RES;STT;1234567890;..."
        );

        assertTrue(plan.processTelemetry());
        assertTrue(plan.processCommandResponse());
    }

    /**
     * Encaminha a mensagem de presença.
     */
    @Test
    void shouldRouteHeartbeat() {

        var plan = route("ALV;1234567890");

        assertTrue(plan.processHeartbeat());
        assertFalse(plan.processTelemetry());
    }

    /**
     * Rejeita uma mensagem para a qual o roteador não encontra trabalho.
     */
    @Test
    void shouldRejectUnknownMessage() {

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> route("UNKNOWN;1234567890")
        );
        assertEquals("UNSUPPORTED_MESSAGE_CAPABILITIES", error.getMessage());
    }
}
