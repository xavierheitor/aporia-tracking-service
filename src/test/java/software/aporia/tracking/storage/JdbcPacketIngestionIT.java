package software.aporia.tracking.storage;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.core.ReceivedFrame;

class JdbcPacketIngestionIT {

    // Test methods for JDBC packet ingestion would go here
    private final JdbcPacketIngestion ingestion
            = new JdbcPacketIngestion(
                    "jdbc:postgresql://localhost:55432/aporia_tracking_v2",
                    "tracking",
                    "tracking_dev"
            );

    /**
     * Test case to verify that a registered device can successfully accept a
     * received frame.
     */
    @Test
    void shouldAcceptRegisteredDevice() {
        ReceivedFrame frame = new ReceivedFrame(
                "suntech",
                "1234567890",
                "ALV;1234567890\r".getBytes(
                        StandardCharsets.US_ASCII
                )
        );

        UUID packetId = ingestion.accept(frame);

        assertNotNull(packetId);
    }

    /**
     * Test case to verify that an unknown or disabled device is rejected when
     * attempting to accept a received frame.
     */
    @Test
    void shouldRejectUnknownDevice() {

        ReceivedFrame frame = new ReceivedFrame(
                "suntech",
                "9999999999",
                "ALV;9999999999\r".getBytes(
                        StandardCharsets.US_ASCII
                )
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ingestion.accept(frame)
        );

        assertEquals(
                "Unknown or disabled device",
                exception.getMessage()
        );
    }

}
