package software.aporia.tracking.protocol;

import software.aporia.tracking.protocol.suntech.SuntechDecoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/**
 * Verifica a resolução de versões de decodificador e a rejeição de versões duplicadas ou
 * desconhecidas.
 *
 * <p>A versão é gravada junto ao pacote bruto; se o registro divergir, o worker não consegue
 * reproduzir a interpretação original.</p>
 */
class DecoderRegistryTest {

    /**
     * Resolve uma versão registrada para a implementação correspondente.
     *
     * <p>A busca deve devolver exatamente a instância registrada, não uma cópia.</p>
     */
    @Test
    void shouldResolveRegisteredDecoder() {

        SuntechDecoder suntech = new SuntechDecoder();

        // A versão retornada pelo próprio decodificador é a chave usada na busca.
        DecoderRegistry registry = new DecoderRegistry(
                List.of(suntech)
        );

        assertSame(
                suntech,
                registry.get("suntech-ascii-v3")
        );
    }

    /**
     * Rejeita uma versão sem implementação registrada.
     *
     * <p>Um pacote antigo pode citar uma versão removida; sem decodificador, ele não pode ser
     * interpretado com outro formato.</p>
     */
    @Test
    void shouldRejectUnknownDecoder() {

        DecoderRegistry registry = new DecoderRegistry(
                List.of(new SuntechDecoder())
        );

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> registry.get("unknown-v1")
        );
        assertEquals("Decoder unavailable: unknown-v1", error.getMessage());
    }

    /**
     * Impede a associação da mesma versão a dois decodificadores.
     *
     * <p>Duas implementações com a mesma versão tornariam a escolha dependente da ordem do
     * registro.</p>
     */
    @Test
    void shouldRejectDuplicateDecoderVersions() {

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> new DecoderRegistry(
                        List.of(
                                // Duas instâncias Suntech expõem a mesma versão persistida.
                                new SuntechDecoder(),
                                new SuntechDecoder()
                        )
                )
        );
        assertEquals("Duplicate decoder version: suntech-ascii-v3", error.getMessage());
    }
}
