package software.aporia.tracking.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Verifica as transições aceitas entre os estados persistidos do comando e a conversão do valor
 * textual gravado no banco.
 *
 * <p>Essas regras protegem a ordem do envio: a transmissão só começa após a reserva e um
 * resultado incerto nunca volta sozinho para a fila.</p>
 */
class DeviceCommandStatusTest {

    /**
     * Confere a passagem de queued para a reserva em sending.
     *
     * <p>A reserva com token separa a escolha do comando da autorização de escrita.</p>
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
     *
     * <p>É a transição que registra o começo da tentativa antes de escrever no socket.</p>
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
     *
     * <p>Somente após essa etapa o resultado local pode ser gravado como sent.</p>
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
     *
     * <p>Pular sending para sent esconderia que nenhuma tentativa foi autorizada.</p>
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
     *
     * <p>Sent significa apenas que os bytes saíram; a resposta comprova a aplicação no
     * equipamento.</p>
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
     *
     * <p>Uma resposta que chega depois do timeout ainda deve ser registrada, em vez de ser
     * descartada.</p>
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
     *
     * <p>Responded é terminal; recolocar na fila repetiria uma operação já confirmada.</p>
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
     *
     * <p>Uma resposta só existe depois de uma escrita ou de um resultado incerto.</p>
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
     *
     * <p>A recuperação lê a coluna status como texto e precisa reconstruir o enum exato.</p>
     */
    @Test
    void shouldMapDatabaseStatus() {
        assertEquals(
                DeviceCommandStatus.DELIVERY_FAILED,
                // A comparação é exata: a coluna usa snake_case minúsculo.
                DeviceCommandStatus.fromDatabase("delivery_failed")
        );
    }

    /**
     * Permite classificar uma reserva interrompida como entrega incerta.
     *
     * <p>A reserva expirada pode ter começado a escrever; por isso não vira falha definitiva.</p>
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
     *
     * <p>Reenviar poderia duplicar a operação no rastreador.</p>
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
     *
     * <p>A resposta real resolve a ambiguidade sem exigir novo envio.</p>
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
     *
     * <p>Esse estado é gravado pela recuperação e precisa voltar ao domínio sem perda.</p>
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
