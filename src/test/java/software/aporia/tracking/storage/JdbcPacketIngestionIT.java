package software.aporia.tracking.storage;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.core.ReceivedFrame;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

class JdbcPacketIngestionIT {

    /**
     * Test case to verify that a registered device can successfully accept a
     * received frame.
     */
    @Test
    void shouldAcceptRegisteredDevice() throws SQLException {
        try (var dataSource = TestDatabase.createDataSource();
             var fixtures = new TestFixtures(dataSource, "9876500061")) {
            String identity = fixtures.deviceIdentity();
            ReceivedFrame frame = new ReceivedFrame(
                    "suntech",
                    identity,
                    ("ALV;" + identity + "\r").getBytes(StandardCharsets.US_ASCII)
            );

            UUID packetId = new JdbcPacketIngestion(dataSource).accept(frame);

            assertNotNull(packetId);
        }
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

        try (var dataSource = TestDatabase.createDataSource()) {
            var ingestion = new JdbcPacketIngestion(dataSource);
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

}
