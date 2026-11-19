package software.aporia.tracking.storage;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import software.aporia.tracking.core.ReceivedFrame;

class JdbcPacketIngestionIT {

    private static HikariDataSource dataSource;
    private static JdbcPacketIngestion ingestion;

    // Test methods for JDBC packet ingestion would go here
    @BeforeAll
    static void setup() {
        dataSource = Database.createDataSource();
        ingestion = new JdbcPacketIngestion(dataSource);
    }

    @AfterAll
    static void cleanup() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

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
