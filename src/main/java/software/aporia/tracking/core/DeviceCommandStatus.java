
package software.aporia.tracking.core;

public enum DeviceCommandStatus {

    QUEUED("queued"),
    SENDING("sending"),
    TRANSMITTING("transmitting"),
    SENT("sent"),
    RESPONDED("responded"),
    TIMEOUT("timeout"),
    DELIVERY_FAILED("delivery_failed"),
    DELIVERY_UNKNOWN("delivery_unknown"),
    CANCELLED("cancelled");

    private final String databaseValue;

    DeviceCommandStatus(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public boolean canTransitionTo(DeviceCommandStatus next) {

        if (next == null) {
            return false;
        }

        return switch (this) {

            case QUEUED ->
                next == SENDING
                || next == TIMEOUT
                || next == CANCELLED;

            case SENDING ->
                next == TRANSMITTING
                || next == DELIVERY_FAILED
                || next == DELIVERY_UNKNOWN;

            case TRANSMITTING ->
                next == SENT
                || next == DELIVERY_UNKNOWN;

            case SENT ->
                next == RESPONDED
                || next == TIMEOUT;

            case TIMEOUT, DELIVERY_UNKNOWN ->
                next == RESPONDED;

            case RESPONDED, DELIVERY_FAILED, CANCELLED ->
                false;
        };
    }

    public static DeviceCommandStatus fromDatabase(
            String value
    ) {

        for (DeviceCommandStatus status : values()) {

            if (status.databaseValue.equals(value)) {
                return status;
            }
        }

        throw new IllegalArgumentException(
                "UNKNOWN_COMMAND_STATUS: " + value
        );
    }
}
