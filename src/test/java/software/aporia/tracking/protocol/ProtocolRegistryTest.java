package software.aporia.tracking.protocol;

import software.aporia.tracking.protocol.suntech.SuntechAdapter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/**
 * Exercita a seleção de adaptadores e a rejeição de registros conflitantes.
 *
 * <p>O adaptador resolve o protocolo do cadastro e define a sessão usada na conexão; uma
 * colisão de identificadores tornaria a escolha implícita.</p>
 */
class ProtocolRegistryTest {

    /**
     * Resolve o adaptador pelo identificador registrado.
     *
     * <p>A busca deve devolver a mesma instância, preservando o estado de configuração do
     * adaptador.</p>
     */
    @Test
    void shouldResolveRegisteredAdapter() {

        SuntechAdapter suntech = new SuntechAdapter();

        ProtocolRegistry registry = new ProtocolRegistry(
                List.of(suntech)
        );

        assertSame(
                suntech,
                registry.get("suntech")
        );
    }

    /**
     * Rejeita um protocolo sem adaptador disponível.
     *
     * <p>O identificador vem do cadastro; um valor divergente não pode escolher outro
     * adaptador.</p>
     */
    @Test
    void shouldRejectUnknownProtocol() {

        ProtocolRegistry registry = new ProtocolRegistry(
                List.of(new SuntechAdapter())
        );

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> registry.get("unknown")
        );
        assertEquals("Unsupported protocol: unknown", error.getMessage());
    }

    /**
     * Impede a substituição silenciosa de um adaptador de mesmo identificador.
     *
     * <p>O registro falha em vez de manter apenas o último adaptador adicionado.</p>
     */
    @Test
    void shouldRejectDuplicateProtocolIds() {

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> new ProtocolRegistry(
                        List.of(
                                new SuntechAdapter(),
                                new SuntechAdapter()
                        )
                )
        );
        assertEquals("Duplicate protocol: suntech", error.getMessage());
    }
}
