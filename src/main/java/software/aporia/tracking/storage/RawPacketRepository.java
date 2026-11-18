package software.aporia.tracking.storage;

import java.util.Optional;
import java.util.UUID;

import software.aporia.tracking.core.RawPacket;

public interface RawPacketRepository {

    void save(RawPacket packet);

    Optional<RawPacket> findById(UUID id);

}
