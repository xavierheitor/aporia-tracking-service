package software.aporia.tracking.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** Verifica quais transições são permitidas entre os estados do comando. */
class DeviceCommandStatusTest {

    /**
     * Confere a passagem de queued para a reserva em sending.
     */
    @Test
    void shouldAllowQueueToSending() {
        assertTrue(
                DeviceCommandStatus.QUEUED.canTransitionTo(
                        DeviceCommandStatus.SENDING
                )
        );
    }

    /**
     * Confere que uma reserva pode iniciar a transmissão.
     */
    @Test
    void shouldAllowSendingToTransmitting() {
        assertTrue(
                DeviceCommandStatus.SENDING.canTransitionTo(
                        DeviceCommandStatus.TRANSMITTING
                )
        );
    }

    /**
     * Permite concluir a escrita a partir de transmitting.
     */
    @Test
    void shouldAllowTransmittingToSent() {
        assertTrue(
                DeviceCommandStatus.TRANSMITTING.canTransitionTo(
                        DeviceCommandStatus.SENT
                )
        );
    }

    /**
     * Exige a etapa de transmissão antes de concluir a escrita.
     */
    @Test
    void shouldRejectSendingDirectlyToSent() {
        assertFalse(
                DeviceCommandStatus.SENDING.canTransitionTo(
                        DeviceCommandStatus.SENT
                )
        );
    }

    /**
     * Permite registrar uma resposta após a escrita local.
     */
    @Test
    void shouldAllowSentToResponded() {
        assertTrue(
                DeviceCommandStatus.SENT.canTransitionTo(
                        DeviceCommandStatus.RESPONDED
                )
        );
    }

    /**
     * Verifica a transição prevista para uma resposta tardia.
     */
    @Test
    void shouldAllowLateResponse() {
        assertTrue(
                DeviceCommandStatus.TIMEOUT.canTransitionTo(
                        DeviceCommandStatus.RESPONDED
                )
        );
    }

    /**
     * Impede que um comando respondido volte à fila.
     */
    @Test
    void shouldRejectCompletedCommandRequeue() {
        assertFalse(
                DeviceCommandStatus.RESPONDED.canTransitionTo(
                        DeviceCommandStatus.QUEUED
                )
        );
    }

    /**
     * Rejeita a passagem direta da fila para o estado respondido.
     */
    @Test
    void shouldRejectInvalidTransition() {
        assertFalse(
                DeviceCommandStatus.QUEUED.canTransitionTo(
                        DeviceCommandStatus.RESPONDED
                )
        );
    }

    /**
     * Converte o valor textual persistido no estado de domínio correspondente.
     */
    @Test
    void shouldMapDatabaseStatus() {
        assertEquals(
                DeviceCommandStatus.DELIVERY_FAILED,
                DeviceCommandStatus.fromDatabase("delivery_failed")
        );
    }

    /**
     * Permite classificar uma reserva interrompida como entrega incerta.
     */
    @Test
    void shouldAllowSendingToDeliveryUnknown() {
        assertTrue(
                DeviceCommandStatus.SENDING.canTransitionTo(
                        DeviceCommandStatus.DELIVERY_UNKNOWN
                )
        );
    }

    /**
     * Impede recolocar automaticamente uma entrega incerta na fila.
     */
    @Test
    void shouldNotAutomaticallyRetryUnknownDelivery() {
        assertFalse(
                DeviceCommandStatus.DELIVERY_UNKNOWN.canTransitionTo(
                        DeviceCommandStatus.QUEUED
                )
        );
    }

    /**
     * Exercita a reconciliação de uma entrega incerta quando há confirmação posterior.
     */
    @Test
    void shouldAllowReconciliationOfUnknownDelivery() {
        assertTrue(
                DeviceCommandStatus.DELIVERY_UNKNOWN.canTransitionTo(
                        DeviceCommandStatus.RESPONDED
                )
        );
    }

    /**
     * Reconhece o valor persistido de entrega incerta.
     */
    @Test
    void shouldMapDeliveryUnknownFromDatabase() {
        assertEquals(
                DeviceCommandStatus.DELIVERY_UNKNOWN,
                DeviceCommandStatus.fromDatabase(
                        "delivery_unknown"
                )
        );
    }

}
