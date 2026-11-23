package software.aporia.tracking.protocol;

import software.aporia.tracking.protocol.suntech.SuntechDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.core.Telemetry;

/** Demonstra o registro de um decodificador alternativo pela versão. */
class DecoderRegistryAlternativeTest {

    /**
     * Exercita a seleção de uma implementação alternativa pela versão registrada.
     */
    @Test
    void shouldUseAlternativeDecoder() {

        TelemetryDecoder alternative = new TelemetryDecoder() {

            /**
             * Identifica o protocolo declarado pelo decodificador de teste.
             *
             * @return identificador do protocolo desta implementação
             */
            @Override public String protocolId() { return "example"; }

            /**
             * Distingue a implementação alternativa no registro de versões.
             *
             * @return versão usada para interpretar os bytes persistidos
             */
            @Override
            public String decoderVersion() {
                return "example-protocol-v1";
            }

            /**
             * Fornece o resultado de decodificação usado pelo cenário.
             *
             * @param frame quadro recebido para interpretação ou ingestão
             * @param identity identidade declarada pelo rastreador no protocolo
             * @return identidade, leituras e eventual resposta presentes no pacote
             */
            @Override
            public DecodedPacket decodePacket(byte[] frame, String identity) {
                return new DecodedPacket(identity, List.of(new Telemetry(
                        "1234567890",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )), null);
            }
        };

        DecoderRegistry registry = new DecoderRegistry(
                List.of(
                        new SuntechDecoder(),
                        alternative
                )
        );

        TelemetryDecoder selected
                = registry.get("example-protocol-v1");

        Telemetry telemetry = selected.decodePacket(
                "EXAMPLE".getBytes(StandardCharsets.US_ASCII), "1234567890"
        ).readings().getFirst();

        assertSame(alternative, selected);
        assertEquals(
                "1234567890",
                telemetry.deviceIdentity()
        );
    }
}
