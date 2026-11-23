package software.aporia.tracking.protocol;

import java.util.List;
import software.aporia.tracking.core.Telemetry;

/**
 * Agrupa a identidade, as leituras em ordem de recepção e o tipo de resposta de comando quando presente.
 *
 * @param identity identidade declarada pelo equipamento no protocolo
 * @param readings leituras na ordem em que aparecem no pacote
 * @param responseType tipo de resposta quando há conteúdo de comando
 */
public record DecodedPacket(String identity, List<Telemetry> readings, String responseType) {
    public DecodedPacket {
        readings = List.copyOf(readings);
    }
}
