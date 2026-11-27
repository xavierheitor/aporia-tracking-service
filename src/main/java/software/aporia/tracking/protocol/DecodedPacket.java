package software.aporia.tracking.protocol;

import java.util.List;
import software.aporia.tracking.core.Telemetry;

/**
 * Agrupa a identidade, as leituras em ordem de recepção e o tipo de resposta de comando quando presente.
 *
 * <p>Uma lista vazia é válida para respostas sem telemetria. O tipo de resposta descreve o conteúdo e não
 * identifica, sozinho, qual comando o originou; nulo significa que o pacote não trouxe resposta. A lista é
 * copiada para uma visão imutável, e a instância pode ser compartilhada entre threads.</p>
 *
 * @param identity identidade declarada pelo equipamento no protocolo
 * @param readings leituras na ordem em que aparecem no pacote
 * @param responseType tipo de resposta quando há conteúdo de comando
 */
public record DecodedPacket(String identity, List<Telemetry> readings, String responseType) {
    /**
     * Preserva a ordem das leituras em uma lista imutável para o worker.
     *
     * @param identity identidade declarada pelo rastreador no protocolo
     * @param readings leituras na ordem do pacote
     * @param responseType tipo da resposta de comando
     * @throws NullPointerException se a lista ou algum elemento for nulo
     */
    public DecodedPacket {
        // List.copyOf tira uma cópia e congela a ordem recebida, impedindo alterações posteriores.
        readings = List.copyOf(readings);
    }
}
