package software.aporia.tracking.protocol;

import org.jspecify.annotations.NonNull;

import software.aporia.tracking.protocol.suntech.SuntechDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.core.Telemetry;

/**
 * Demonstra que a versão persistida — e não o identificador do protocolo — seleciona o
 * decodificador.
 *
 * <p>Isso permite registrar uma implementação alternativa sem alterar o protocolo do cadastro,
 * desde que a versão gravada no pacote seja única.</p>
 */
class DecoderRegistryAlternativeTest {

    /**
     * Exercita a seleção de uma implementação alternativa pela versão registrada.
     *
     * <p>O quadro de exemplo não precisa ser Suntech: o teste verifica qual decodificador foi
     * resolvido, não o formato do payload.</p>
     */
    @Test
    void shouldUseAlternativeDecoder() {

        TelemetryDecoder alternative = new TelemetryDecoder() {

            /**
             * Identifica o protocolo declarado pelo decodificador de teste.
             *
             * @return identificador do protocolo desta implementação
             */
            @Override public @NonNull String protocolId() { return "example"; }

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
                // A leitura só carrega a identidade; os demais campos ficam nulos neste exemplo.
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

        // O registro contém o Suntech e a versão alternativa; a busca usa a versão persistida.
        DecoderRegistry registry = new DecoderRegistry(
                List.of(
                        new SuntechDecoder(),
                        alternative
                )
        );

        TelemetryDecoder selected
                = registry.get("example-protocol-v1");

        Telemetry telemetry = selected.decodePacket(
                // Os bytes não são interpretados de verdade pelo decodificador de exemplo.
                "EXAMPLE".getBytes(StandardCharsets.US_ASCII), "1234567890"
        ).readings().getFirst();

        // assertSame confirma a instância registrada, não apenas uma versão equivalente.
        assertSame(alternative, selected);
        assertEquals(
                "1234567890",
                telemetry.deviceIdentity()
        );
    }
}
