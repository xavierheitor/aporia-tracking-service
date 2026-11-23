package software.aporia.tracking.protocol;

import software.aporia.tracking.protocol.suntech.SuntechDecoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/** Verifica a resolução de versões de decodificador e a rejeição de versões duplicadas ou desconhecidas. */
class DecoderRegistryTest {

    /**
     * Resolve uma versão registrada para a implementação correspondente.
     */
    @Test
    void shouldResolveRegisteredDecoder() {

        SuntechDecoder suntech = new SuntechDecoder();

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
     */
    @Test
    void shouldRejectDuplicateDecoderVersions() {

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> new DecoderRegistry(
                        List.of(
                                new SuntechDecoder(),
                                new SuntechDecoder()
                        )
                )
        );
        assertEquals("Duplicate decoder version: suntech-ascii-v3", error.getMessage());
    }
}
