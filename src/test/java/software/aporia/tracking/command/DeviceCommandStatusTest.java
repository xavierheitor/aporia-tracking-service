package software.aporia.tracking.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** Verifica quais transições são permitidas entre os estados do comando. */
class DeviceCommandStatusTest {

    @Test
    void shouldAllowQueueToSending() {
        assertTrue(
                DeviceCommandStatus.QUEUED.canTransitionTo(
                        DeviceCommandStatus.SENDING
                )
        );
    }

    @Test
    void shouldAllowSendingToTransmitting() {
        assertTrue(
                DeviceCommandStatus.SENDING.canTransitionTo(
                        DeviceCommandStatus.TRANSMITTING
                )
        );
    }

    @Test
    void shouldAllowTransmittingToSent() {
        assertTrue(
                DeviceCommandStatus.TRANSMITTING.canTransitionTo(
                        DeviceCommandStatus.SENT
                )
        );
    }

    @Test
    void shouldRejectSendingDirectlyToSent() {
        assertFalse(
                DeviceCommandStatus.SENDING.canTransitionTo(
                        DeviceCommandStatus.SENT
                )
        );
    }

    @Test
    void shouldAllowSentToResponded() {
        assertTrue(
                DeviceCommandStatus.SENT.canTransitionTo(
                        DeviceCommandStatus.RESPONDED
                )
        );
    }

    @Test
    void shouldAllowLateResponse() {
        assertTrue(
                DeviceCommandStatus.TIMEOUT.canTransitionTo(
                        DeviceCommandStatus.RESPONDED
                )
        );
    }

    @Test
    void shouldRejectCompletedCommandRequeue() {
        assertFalse(
                DeviceCommandStatus.RESPONDED.canTransitionTo(
                        DeviceCommandStatus.QUEUED
                )
        );
    }

    @Test
    void shouldRejectInvalidTransition() {
        assertFalse(
                DeviceCommandStatus.QUEUED.canTransitionTo(
                        DeviceCommandStatus.RESPONDED
                )
        );
    }

    @Test
    void shouldMapDatabaseStatus() {
        assertEquals(
                DeviceCommandStatus.DELIVERY_FAILED,
                DeviceCommandStatus.fromDatabase("delivery_failed")
        );
    }

    @Test
    void shouldAllowSendingToDeliveryUnknown() {
        assertTrue(
                DeviceCommandStatus.SENDING.canTransitionTo(
                        DeviceCommandStatus.DELIVERY_UNKNOWN
                )
        );
    }

    @Test
    void shouldNotAutomaticallyRetryUnknownDelivery() {
        assertFalse(
                DeviceCommandStatus.DELIVERY_UNKNOWN.canTransitionTo(
                        DeviceCommandStatus.QUEUED
                )
        );
    }

    @Test
    void shouldAllowReconciliationOfUnknownDelivery() {
        assertTrue(
                DeviceCommandStatus.DELIVERY_UNKNOWN.canTransitionTo(
                        DeviceCommandStatus.RESPONDED
                )
        );
    }

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
