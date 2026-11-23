package software.aporia.tracking.storage.packet;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import software.aporia.tracking.core.ReceivedFrame;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifica a ingestão para um equipamento cadastrado e o tratamento de identidades desconhecidas. */
class JdbcPacketIngestionIT {

    /** Confere a ingestão de um quadro de dispositivo cadastrado. */
    @Test
    void shouldAcceptRegisteredDevice() throws SQLException {
        try (var dataSource = TestDatabase.createDataSource();
             var fixtures = new TestFixtures(dataSource, "9876500061")) {
            String identity = fixtures.deviceIdentity();
            ReceivedFrame frame = new ReceivedFrame(
                    "suntech",
                    identity,
                    ("ALV;" + identity + "\r").getBytes(StandardCharsets.US_ASCII), "suntech-ascii-v3"
            );

            UUID packetId = new JdbcPacketIngestion(dataSource).accept(frame);

            assertNotNull(packetId);
        }
    }

    /** Verifica a rejeição de um quadro cujo dispositivo não está habilitado no cadastro. */
    @Test
    void shouldRejectUnknownDevice() {

        ReceivedFrame frame = new ReceivedFrame(
                "suntech",
                "9999999999",
                "ALV;9999999999\r".getBytes(
                        StandardCharsets.US_ASCII
                ), "suntech-ascii-v3"
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
