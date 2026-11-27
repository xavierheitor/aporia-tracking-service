package software.aporia.tracking.gateway;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protege a seleção do canal de comando: somente a sessão atual e conectada do par tenant e
 * dispositivo pode receber bytes, e uma sessão substituída não migra a escrita para o socket
 * novo.
 *
 * <p>
 * Os cenários usam o {@link SessionRegistry} compartilhado e sockets de loopback reais.
 * {@code isConnected()} e {@code send()} são métodos default de
 * {@link software.aporia.tracking.command.dispatcher.CommandTransport} que delegam a
 * {@code connect()}; as falhas esperadas seguem os códigos do contrato de transporte.
 * </p>
 */
class SessionCommandTransportTest {

    /** Protocolo configurado para o equipamento do teste. */
    private static final String PROTOCOL = "suntech";
    /** Identidade do equipamento usada no cenário. */
    private static final String IDENTITY = "1234567890";

    /**
     * Cenário: registro sem sessão para o dispositivo consultado. Ação: perguntar ao
     * transporte se há conexão. Resultado: falso, pois não existe endpoint a selecionar.
     */
    @Test
    void shouldReportDisconnectedWhenSessionDoesNotExist() {

        SessionRegistry registry = new SessionRegistry();

        SessionCommandTransport transport
                = new SessionCommandTransport(registry);

        // Sem sessão registrada, o transporte não encontra endpoint para o par consultado.
        assertFalse(
                transport.isConnected(
                        UUID.randomUUID(),
                        UUID.randomUUID()
                )
        );
    }

    /**
     * Cenário: registro sem sessão para o dispositivo. Ação: enviar um comando. Resultado:
     * {@link IOException} com {@code DEVICE_SESSION_NOT_AVAILABLE}, sem escolher canal.
     */
    @Test
    void shouldRejectSendingWithoutSession() {

        SessionRegistry registry = new SessionRegistry();

        SessionCommandTransport transport
                = new SessionCommandTransport(registry);

        // O método default converte a ausência de endpoint na falha de transporte do contrato.
        var error = assertThrows(
                IOException.class,
                () -> transport.send(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "TEST\r".getBytes(
                                StandardCharsets.US_ASCII
                        )
                )
        );
        assertEquals("DEVICE_SESSION_NOT_AVAILABLE", error.getMessage());
    }

    /**
     * Cenário: sessão conectada registrada para tenant e dispositivo. Ação: enviar um comando
     * pelo transporte. Resultado: o equipamento lê exatamente o payload e, após o
     * desregistro, o transporte passa a reportar desconexão.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldSendExactBytesToConnectedDevice()
            throws Exception {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        byte[] payload = ("CMD;" + IDENTITY + ";03;04;1\r").getBytes(StandardCharsets.US_ASCII);

        // listener atende as duas pontas; client é o equipamento e gatewaySocket é a sessão.
        try (ServerSocket server = new ServerSocket(0); Socket client = new Socket(
                "127.0.0.1",
                server.getLocalPort()
        ); Socket gatewaySocket = server.accept()) {

            // Prazos de leitura impedem que o teste fique bloqueado se o comando não chegar.
            gatewaySocket.setSoTimeout(2000);
            client.setSoTimeout(2000);

            SessionRegistry registry = new SessionRegistry();

            DeviceSession session = new DeviceSession(
                    tenantId,
                    deviceId,
                    PROTOCOL,
                    IDENTITY,
                    gatewaySocket
            );

            registry.register(session);

            SessionCommandTransport transport
                    = new SessionCommandTransport(registry);

            assertTrue(
                    transport.isConnected(tenantId, deviceId)
            );

            transport.send(
                    tenantId,
                    deviceId,
                    payload
            );

            byte[] received = client
                    .getInputStream()
                    .readNBytes(payload.length);

            assertArrayEquals(payload, received);

            registry.unregister(session);

            // Sem a sessão no registro, o dispositivo volta a ser offline para o dispatcher.
            assertFalse(
                    transport.isConnected(tenantId, deviceId)
            );
        }
    }

    /**
     * Cenário: sessão registrada e depois invalidada, mas ainda presente no mapa. Ação:
     * consultar e enviar. Resultado: desconexão e {@code DEVICE_SESSION_NOT_AVAILABLE}.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectInvalidatedSession()
            throws Exception {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        try (ServerSocket server = new ServerSocket(0); Socket _ = new Socket(
                "127.0.0.1",
                server.getLocalPort()
        ); Socket gatewaySocket = server.accept()) {

            SessionRegistry registry = new SessionRegistry();

            DeviceSession session = new DeviceSession(
                    tenantId,
                    deviceId,
                    PROTOCOL,
                    IDENTITY,
                    gatewaySocket
            );

            registry.register(session);
            session.invalidate();

            // A sessão continua no mapa; o filtro de isConnected() é que a torna indisponível.
            SessionCommandTransport transport
                    = new SessionCommandTransport(registry);

            assertFalse(
                    transport.isConnected(tenantId, deviceId)
            );

            var error = assertThrows(
                    IOException.class,
                    () -> transport.send(
                            tenantId,
                            deviceId,
                            "TEST\r".getBytes(
                                    StandardCharsets.US_ASCII
                            )
                    )
            );
            assertEquals("DEVICE_SESSION_NOT_AVAILABLE", error.getMessage());
        }
    }

    /**
     * Cenário: sessão registrada para um tenant e consulta feita com outro tenant. Ação:
     * verificar conexão e enviar. Resultado: nenhum canal encontrado, sem escrita no socket.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectCrossTenantAccess()
            throws Exception {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        try (ServerSocket server = new ServerSocket(0); Socket _ = new Socket(
                "127.0.0.1",
                server.getLocalPort()
        ); Socket gatewaySocket = server.accept()) {

            SessionRegistry registry = new SessionRegistry();

            registry.register(
                    new DeviceSession(
                            tenantId,
                            deviceId,
                            PROTOCOL,
                            IDENTITY,
                            gatewaySocket
                    )
            );

            SessionCommandTransport transport
                    = new SessionCommandTransport(registry);

            UUID anotherTenant = UUID.randomUUID();

            // A sessão pertence a tenantId; a consulta abaixo usa outro tenant.
            assertFalse(
                    transport.isConnected(
                            anotherTenant,
                            deviceId
                    )
            );

            // Mesmo deviceId não atravessa o isolamento por tenant.
            var error = assertThrows(
                    IOException.class,
                    () -> transport.send(
                            anotherTenant,
                            deviceId,
                            "TEST\r".getBytes(
                                    StandardCharsets.US_ASCII
                            )
                    )
            );
            assertEquals("DEVICE_SESSION_NOT_AVAILABLE", error.getMessage());
        }
    }
    /**
     * Cenário: endpoint selecionado para a sessão antiga e, em seguida, uma reconexão do mesmo
     * dispositivo. Ação: enviar pelo endpoint já selecionado. Resultado:
     * {@code DEVICE_DISCONNECTED} e nenhum byte escrito no socket antigo ou no novo.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void selectedEndpointNeverSwitchesToReplacementSocket() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        // Cada conexão tem um lado do rastreador (oldPeer/newPeer) e um lado do gateway.
        try (var listener = new ServerSocket(0);
             var oldPeer = new Socket("127.0.0.1", listener.getLocalPort()); var oldSocket = listener.accept();
             var newPeer = new Socket("127.0.0.1", listener.getLocalPort()); var newSocket = listener.accept()) {
            var registry = new SessionRegistry();
            registry.register(new DeviceSession(tenant, device, PROTOCOL, IDENTITY, oldSocket));
            var transport = new SessionCommandTransport(registry);
            var selected = transport.connect(tenant, device).orElseThrow();
            // A reconexão invalida a sessão antiga sem fechar o socket que o endpoint já capturou.
            registry.register(new DeviceSession(tenant, device, PROTOCOL, IDENTITY, newSocket));
            byte[] bytes = "test".getBytes(StandardCharsets.US_ASCII);
            var error = assertThrows(IOException.class, () -> selected.send(bytes));
            assertEquals("DEVICE_DISCONNECTED", error.getMessage());
            // Após shutdownOutput, -1 comprova que nenhum byte foi escrito em qualquer socket.
            oldSocket.shutdownOutput();
            newSocket.shutdownOutput();
            assertEquals(-1, oldPeer.getInputStream().read());
            assertEquals(-1, newPeer.getInputStream().read());
        }
    }
}
