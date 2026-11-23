package software.aporia.tracking.storage.packet;

import java.util.Optional;
import java.util.UUID;

import software.aporia.tracking.core.RawPacket;

/** Define as operações de gravação e consulta de um pacote bruto por identificador. */
public interface RawPacketRepository {

    /**
     * Persiste o conteúdo bruto e os dados de recepção do pacote.
     *
     * @param packet pacote bruto a processar
     */
    void save(RawPacket packet);

    /**
     * Consulta uma recepção pelo UUID e devolve um resultado vazio se ela não existir.
     *
     * @param id identificador do registro consultado
     * @return pacote correspondente ao UUID, se encontrado
     */
    Optional<RawPacket> findById(UUID id);

}
