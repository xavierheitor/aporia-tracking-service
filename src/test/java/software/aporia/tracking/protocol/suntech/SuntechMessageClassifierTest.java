package software.aporia.tracking.protocol.suntech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/** Relaciona cabeçalhos conhecidos e desconhecidos às categorias tratadas pelo serviço. */
class SuntechMessageClassifierTest {

    /** Classificador de cabeçalhos exercitado pelos testes. */
    private final SuntechMessageClassifier classifier
            = new SuntechMessageClassifier();

    /**
     * Classifica os cabeçalhos de posição como telemetria.
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
     */
    @Test
    void shouldClassifyUnknownMessages() {
        assertEquals(
                ProtocolMessageKind.UNKNOWN,
                classifier.classify("UNKNOWN")
        );
    }
}
