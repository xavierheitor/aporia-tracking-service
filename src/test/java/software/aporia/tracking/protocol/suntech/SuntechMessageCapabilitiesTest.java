package software.aporia.tracking.protocol.suntech;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Verifica a identificação das capacidades de mensagens Suntech, inclusive respostas compostas.
 *
 * <p>Uma resposta pode conter uma posição embutida; reconhecer as duas capacidades evita perder
 * a telemetria ou deixar de registrar a resposta.</p>
 */
class SuntechMessageCapabilitiesTest {

    /**
     * Obtém as capacidades reconhecidas nos bytes do exemplo Suntech.
     *
     * @param message mensagem do protocolo usada no cenário
     * @return capacidades reconhecidas no exemplo
     */
    private ProtocolMessageCapabilities classify(String message) {
        return SuntechMessageCapabilities.classify(
                message.getBytes(StandardCharsets.US_ASCII)
        );
    }

    /**
     * Confere a capacidade de telemetria de uma mensagem STT.
     *
     * <p>O conteúdo após o cabeçalho não participa da classificação neste estágio.</p>
     */
    @Test
    void shouldClassifySttAsTelemetry() {

        var result = classify("STT;1234567890;...");

        assertTrue(result.hasTelemetry());
        assertFalse(result.hasCommandResponse());
    }

    /**
     * Confere que ASTT também ativa o processamento de telemetria.
     *
     * <p>ASTT é a variante estendida de posição e deve seguir o mesmo caminho de STT.</p>
     */
    @Test
    void shouldClassifyAsttAsTelemetry() {

        var result = classify("ASTT;1234567890;...");

        assertTrue(result.hasTelemetry());
    }

    /**
     * Identifica a mensagem de presença ALV.
     *
     * <p>ALV não carrega posição; por isso só o processamento de presença é ativado.</p>
     */
    @Test
    void shouldClassifyHeartbeat() {

        var result = classify("ALV;1234567890;...");

        assertTrue(result.hasHeartbeat());
        assertFalse(result.hasTelemetry());
    }

    /**
     * Reconhece o conteúdo de resposta a comando.
     *
     * <p>RES sem STT/ASTT é apenas resposta; não há leitura a extrair.</p>
     */
    @Test
    void shouldClassifyCommandResponse() {

        var result = classify(
                "RES;1234567890;03;04;01;STB.201.1.1.11"
        );

        assertTrue(result.hasCommandResponse());
        assertFalse(result.hasTelemetry());
    }

    /**
     * Confere que uma resposta com posição ativa ambos os processamentos.
     *
     * <p>O prefixo RES pede o registro da resposta e o STT embutido pede telemetria.</p>
     */
    @Test
    void shouldClassifyResponseWithTelemetry() {

        var result = classify(
                "RES;STT;1234567890;013831;20261120;08:45:00;"
                + "-16.6700;-49.2500;45.5;1"
        );

        assertTrue(result.hasCommandResponse());
        assertTrue(result.hasTelemetry());
    }

    /**
     * Reconhece a resposta de programação pelo cabeçalho RPR.
     *
     * <p>RPR reporta o resultado da programação e não contém posição.</p>
     */
    @Test
    void shouldClassifyRprAsCommandResponse() {

        var result = classify(
                "RPR;1234567890;OK;10;13#0"
        );

        assertTrue(result.hasCommandResponse());
        assertFalse(result.hasTelemetry());
    }

    /**
     * Confere a ausência de capacidades para um cabeçalho desconhecido.
     *
     * <p>Cabeçalho não mapeado não ativa nenhum processamento, em vez de assumir telemetria.</p>
     */
    @Test
    void shouldReturnEmptyForUnknownMessage() {

        var result = classify("UNKNOWN;1234567890");

        assertTrue(result.values().isEmpty());
    }

    /**
     * Confere a ausência de capacidades quando nenhum conteúdo chega.
     *
     * <p>Sem bytes não há cabeçalho para classificar.</p>
     */
    @Test
    void shouldReturnEmptyForEmptyPayload() {

        var result = SuntechMessageCapabilities.classify(
                new byte[0]
        );

        assertTrue(result.values().isEmpty());
    }
}
