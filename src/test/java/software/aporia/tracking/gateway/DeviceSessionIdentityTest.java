package software.aporia.tracking.gateway;

import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/**
 * Protege o vínculo entre a conexão autorizada e a identidade declarada no início da sessão.
 *
 * <p>
 * O contrato é local ao gateway: uma conexão já admitida não pode trocar de identidade e
 * passar a endereçar comandos a outro equipamento. Os cenários não abrem conexão TCP nem
 * usam banco; o {@link Socket} apenas preenche o vínculo exigido por {@link DeviceSession}.
 * </p>
 */
class DeviceSessionIdentityTest {

    /**
     * Monta a sessão com tenant, dispositivo, protocolo e identidade conhecidos.
     *
     * <p>
     * A ação dos testes é validar uma identidade recebida; o resultado esperado é que a
     * sessão preserve a identidade fixada na admissão. O socket não é conectado porque
     * nenhum cenário desta classe escreve bytes.
     * </p>
     *
     * @return sessão vinculada à identidade de teste
     */
    private DeviceSession createSession() {
        return new DeviceSession(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "suntech",
                "1234567890",
                new Socket()
        );
    }

    /**
     * Cenário: sessão admitida com a identidade {@code 1234567890}. Ação: validar a mesma
     * identidade em um quadro seguinte. Resultado: nenhuma exceção e a conexão pode continuar.
     */
    @Test
    void shouldAcceptSameIdentity() {
        DeviceSession session = createSession();

        // A comparação é exata; repetir a identidade da admissão não altera a sessão.
        assertDoesNotThrow(
                () -> session.validateIdentity("1234567890")
        );
    }

    /**
     * Cenário: sessão vinculada a {@code 1234567890}. Ação: validar outra identidade na mesma
     * conexão. Resultado: {@link IllegalArgumentException} com o código
     * {@code SESSION_IDENTITY_MISMATCH}.
     */
    @Test
    void shouldRejectDifferentIdentity() {
        DeviceSession session = createSession();

        // A identidade divergente é recusada antes de qualquer envio ou efeito persistido.
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> session.validateIdentity("0987654321")
        );

        // O código da exceção é o contrato observável pelo handler TCP.
        assertEquals(
                "SESSION_IDENTITY_MISMATCH",
                error.getMessage()
        );
    }

    /**
     * Cenário: sessão já vinculada a uma identidade não nula. Ação: validar uma identidade
     * ausente. Resultado: recusa com o mesmo código de divergência, sem {@code NullPointerException}.
     */
    @Test
    void shouldRejectNullIdentity() {
        DeviceSession session = createSession();

        // equals(null) é falso, então a ausência cai na mesma recusa de identidade divergente.
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> session.validateIdentity(null)
        );
        assertEquals("SESSION_IDENTITY_MISMATCH", error.getMessage());
    }
}
