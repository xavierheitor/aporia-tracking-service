package software.aporia.tracking.protocol.suntech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Relaciona cabeçalhos conhecidos e desconhecidos às categorias tratadas pelo serviço.
 *
 * <p>A classificação representa apenas o cabeçalho; respostas compostas com posição são
 * tratadas pelas capacidades, não por este classificador.</p>
 */
class SuntechMessageClassifierTest {

    /** Classificador de cabeçalhos exercitado pelos testes. */
    private final SuntechMessageClassifier classifier
            = new SuntechMessageClassifier();

    /**
     * Classifica os cabeçalhos de posição como telemetria.
     *
     * <p>STT e ASTT são as variantes de posição usadas pelo worker.</p>
     */
    @Test
    void shouldClassifyTelemetry() {
        assertEquals(
                ProtocolMessageKind.TELEMETRY,
                classifier.classify("STT")
        );

        assertEquals(
                ProtocolMessageKind.TELEMETRY,
                classifier.classify("ASTT")
        );
    }

    /**
     * Classifica os cabeçalhos de retorno de comando.
     *
     * <p>RES e RPR retornam o resultado de uma operação enviada ao equipamento.</p>
     */
    @Test
    void shouldClassifyCommandResponses() {
        assertEquals(
                ProtocolMessageKind.COMMAND_RESPONSE,
                classifier.classify("RES")
        );

        assertEquals(
                ProtocolMessageKind.COMMAND_RESPONSE,
                classifier.classify("RPR")
        );
    }

    /**
     * Reconhece ALV como sinal de presença.
     *
     * <p>ALV é o heartbeat sem posição enviado pelo rastreador.</p>
     */
    @Test
    void shouldClassifyHeartbeat() {
        assertEquals(
                ProtocolMessageKind.HEARTBEAT,
                classifier.classify("ALV")
        );
    }

    /**
     * Confere a categoria reservada a cabeçalhos desconhecidos.
     *
     * <p>Um cabeçalho não mapeado precisa de uma categoria explícita e não pode virar
     * telemetria por omissão.</p>
     */
    @Test
    void shouldClassifyUnknownMessages() {
        assertEquals(
                ProtocolMessageKind.UNKNOWN,
                classifier.classify("UNKNOWN")
        );
    }
}
