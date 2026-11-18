package software.aporia.tracking.storage;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.core.RawPacket;

class JdbcRawPacketRepositoryIT {
    // Testes de integração para o repositório JdbcRawPacketRepository

    @Test
    void shouldSaveAndFindPacket() {
        var repository = new JdbcRawPacketRepository(
                "jdbc:postgresql://localhost:55432/aporia_tracking",
                "tracking",
                "tracking_dev"
        );

        var packet = new RawPacket(
                UUID.randomUUID(),
                "suntech",
                "123456789012345",
                new byte[]{0x01, 0x02, 0x03},
                Instant.now()
        );

        repository.save(packet);

        var recovered = repository.findById(packet.id());

        assertTrue(recovered.isPresent());
        assertEquals(packet, recovered.orElseThrow());

    }
}
