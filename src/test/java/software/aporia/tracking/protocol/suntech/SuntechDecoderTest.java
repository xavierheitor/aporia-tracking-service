package software.aporia.tracking.protocol.suntech;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.core.Telemetry;

/**
 * Compara telemetria, heartbeats e posições embutidas em respostas com os valores esperados e
 * exercita campos inválidos do mapa de presença.
 *
 * <p>O mapa de seis dígitos hex determina quais campos vieram no texto; interpretar a ordem
 * errada deslocaria coordenadas e horário sem falhar a leitura.</p>
 */
class SuntechDecoderTest {

    /** Decodificador usado para interpretar os quadros de exemplo. */
    private final SuntechDecoder decoder
            = new SuntechDecoder();

    /**
     * Codifica o quadro de exemplo com o mesmo charset usado pelo equipamento.
     *
     * @param value valor a interpretar
     * @return bytes ASCII da mensagem de exemplo
     */
    private byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Decodifica um sinal de presença sem exigir coordenadas.
     *
     * <p>ALV só confirma que o equipamento está online; os campos de posição devem permanecer
     * nulos.</p>
     */
    @Test
    void shouldDecodeAliveMessage() {

        Telemetry telemetry = decoder.decode(
                ascii("ALV;1234567890\r")
        );

        assertEquals(
                "1234567890",
                telemetry.deviceIdentity()
        );

        assertNull(telemetry.latitude());
        assertNull(telemetry.longitude());

        assertFalse(telemetry.hasPosition());
    }

    /**
     * Rejeita um heartbeat com campos excedentes.
     *
     * <p>ALV válido tem exatamente dois campos; um terceiro indica formato divergente do
     * esperado.</p>
     */
    @Test
    void shouldRejectAliveWithExtraFields() {

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decode(
                        ascii("ALV;1234567890;EXTRA\r")
                )
        );

        assertEquals(
                "INVALID_FIELD_COUNT",
                exception.getMessage()
        );
    }

    /**
     * Compara a posição decodificada com os valores do quadro.
     *
     * <p>O mapa 013831 liga date, time, lat, lon, speed e fix, na ordem em que os campos
     * aparecem no texto.</p>
     */
    @Test
    void shouldDecodePosition() {
        var telemetry = decoder.decode(
                ascii(
                        "STT;1234567890;013831;20261119;13:30:00;"
                        + "-16.6700;-49.2500;45.5;1\r"
                )
        );

        assertEquals("1234567890", telemetry.deviceIdentity());
        assertEquals(-16.6700, telemetry.latitude());
        assertEquals(-49.2500, telemetry.longitude());
        assertEquals(45.5, telemetry.speedKph());
        assertTrue(telemetry.hasPosition());
    }

    /**
     * Rejeita uma latitude fora do intervalo geográfico.
     *
     * <p>-100.0 é um número válido, mas excede o limite de -90 graus aceito para latitude.</p>
     */
    @Test
    void shouldRejectInvalidLatitude() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decode(
                        ascii(
                                "STT;1234567890;013831;20261119;13:30:00;"
                                + "-100.0;-49.2500;45.5;1\r"
                        )
                )
        );

        assertEquals("INVALID_NUMBER", exception.getMessage());
    }

    /**
     * Exige o mapa que determina a ordem e a presença dos campos de posição.
     *
     * <p>Sem o mapa de seis dígitos hex não há como saber quais campos vieram no texto.</p>
     */
    @Test
    void shouldRejectMissingInlineMap() {
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decode(
                        ascii("STT;1234567890\r")
                )
        );

        assertEquals("INLINE_MAP_REQUIRED", error.getMessage());
    }

    /**
     * Verifica a rejeição de mensagem fora dos formatos decodificáveis.
     *
     * <p>O cabeçalho UNKNOWN é recusado pelo enquadrador antes da escolha do formato de
     * decodificação.</p>
     */
    @Test
    void shouldRejectUnsupportedMessage() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decode(
                        ascii("UNKNOWN;1234567890\r")
                )
        );

        assertTrue(exception != null);
    }

    /**
     * Extrai a posição embutida no corpo de uma resposta.
     *
     * <p>O prefixo RES é descartado e o restante é interpretado como se fosse um STT, preservando
     * a mesma telemetria do quadro original.</p>
     */
    @Test
    void shouldDecodeTelemetryInsideCommandResponse() {

        // Reaproveita o mesmo quadro STT nos dois caminhos para comparar as leituras produzidas.
        byte[] original = ascii(
                        "STT;1234567890;013831;20261119;13:30:00;"
                        + "-16.6700;-49.2500;45.5;1\r"
                );

        String stt = new String(
                original,
                StandardCharsets.US_ASCII
        );

        // RES;STT;... mantém o CR e a posição embutida do quadro original.
        byte[] response = ("RES;" + stt).getBytes(StandardCharsets.US_ASCII);

        Telemetry expected = decoder.decode(original);
        Telemetry actual = decoder.decode(response);

        assertEquals(expected, actual);
    }

    /**
     * Decodifica o exemplo StatusReq da revisão 4 e confirma coordenadas e ausência de fix
     * válido.
     *
     * <p>O exemplo tem mapa completo com extensão; as coordenadas existem, mas fix=0 impede
     * tratá-las como posição válida.</p>
     */
    @Test
    void readsStatusReqExampleFromRevisionFour() {
        // Mapa B9FFFF: 22 campos do formato mais um mapa de extensão ao final do quadro.
        var frame = ascii("RES;STT;1234567890;B9FFFF;201;1.1.11;0;20240317;15:07:40;000030A4;"
                + "724;10;0207;34;-22.847418;-47.085902;0.00;0.00;0;0;0;0;0002;0380003F;4.1;2;+34.1;;;;;;\r");
        var decoded = decoder.decodePacket(frame, "1234567890");
        assertEquals("RES", decoded.responseType());
        assertEquals(-22.847418, decoded.readings().getFirst().latitude());
        // As coordenadas foram lidas, mas fix=0 mantém a leitura sem posição válida.
        assertFalse(decoded.readings().getFirst().hasPosition());
    }
}
