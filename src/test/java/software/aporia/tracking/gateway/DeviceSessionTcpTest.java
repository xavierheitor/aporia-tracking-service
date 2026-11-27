package software.aporia.tracking.gateway;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Protege a escrita serializada no socket e a invalidação da sessão após falha, desconexão ou
 * vencimento do prazo de escrita.
 *
 * <p>
 * Os cenários de envio usam sockets de loopback reais. O prazo de escrita usa um socket
 * simulado para bloquear a saída além dos cinco segundos e observar o fechamento pelo
 * temporizador. O contrato é local: {@code send()} só conclui após escrita e flush; uma falha
 * de escrita invalida a sessão e impede novas tentativas.
 * </p>
 */
class DeviceSessionTcpTest {

    /**
     * Cenário: sessão sobre um par de sockets de loopback. Ação: enviar um comando Suntech
     * bruto. Resultado: o equipamento lê exatamente os mesmos bytes, sem framing adicional.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldSendRawCommandOverTcp() throws Exception {

        // Porta zero escolhe uma porta livre; accept() devolve o lado do gateway.
        try (ServerSocket server = new ServerSocket(0); Socket client = new Socket(
                "127.0.0.1",
                server.getLocalPort()
        ); Socket gatewaySocket = server.accept()) {

            // Prazo curto evita que uma falha de escrita trave o teste de loopback.
            client.setSoTimeout(2000);

            DeviceSession session = new DeviceSession(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "suntech",
                    "1234567890",
                    gatewaySocket
            );

            byte[] command = ("CMD;1234567890;03;04;1\r").getBytes(StandardCharsets.US_ASCII);

            session.send(command);

            InputStream input = client.getInputStream();

            byte[] received = input.readNBytes(command.length);

            assertArrayEquals(command, received);
        }
    }

    /**
     * Cenário: sessão ativa sobre sockets de loopback. Ação: enviar um payload vazio.
     * Resultado: recusa com {@code EMPTY_COMMAND_PAYLOAD} antes de qualquer escrita.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectEmptyCommand() throws Exception {

        try (ServerSocket server = new ServerSocket(0); Socket _ = new Socket(
                "127.0.0.1",
                server.getLocalPort()
        ); Socket gatewaySocket = server.accept()) {

            DeviceSession session = new DeviceSession(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "suntech",
                    "1234567890",
                    gatewaySocket
            );

            var error = assertThrows(
                    IllegalArgumentException.class,
                    () -> session.send(new byte[0])
            );
            // A validação ocorre antes de adquirir o bloqueio de escrita ou tocar no socket.
            assertEquals("EMPTY_COMMAND_PAYLOAD", error.getMessage());
        }
    }

    /**
     * Cenário: o lado do gateway é fechado, mas a sessão mantém a referência ao socket.
     * Ação: tentar enviar um comando. Resultado: {@link java.io.IOException} com
     * {@code DEVICE_DISCONNECTED}, sem escrita.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectDisconnectedSocket() throws Exception {

        try (ServerSocket server = new ServerSocket(0); Socket _ = new Socket(
                "127.0.0.1",
                server.getLocalPort()
        )) {
            DeviceSession session;
            // O try-with-resources fecha o socket após a construção, simulando a queda do par.
            try (Socket gatewaySocket = server.accept()) {
                session = new DeviceSession(UUID.randomUUID(), UUID.randomUUID(),
                        "suntech", "1234567890", gatewaySocket);
            }

            byte[] command = ("CMD;1234567890;03;04;1\r").getBytes(StandardCharsets.US_ASCII);

            // isConnected() consulta o estado local e recusa antes de qualquer escrita.
            var error = assertThrows(
                    java.io.IOException.class,
                    () -> session.send(command)
            );
            assertEquals("DEVICE_DISCONNECTED", error.getMessage());
        }
    }

    /**
     * Cenário: sessão ativa e ainda conectada. Ação: invalidar a sessão e tentar novo envio.
     * Resultado: {@code isConnected()} falso e {@code DEVICE_DISCONNECTED} na escrita.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectSendingAfterInvalidation() throws Exception {

        try (ServerSocket server = new ServerSocket(0); Socket _ = new Socket(
                "127.0.0.1",
                server.getLocalPort()
        ); Socket gatewaySocket = server.accept()) {

            DeviceSession session = new DeviceSession(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "suntech",
                    "1234567890",
                    gatewaySocket
            );

            // Antes da invalidação, o socket local ainda está disponível para escrita.
            assertTrue(session.isConnected());

            // invalidate() desliga a sessão sem fechar o socket.
            session.invalidate();

            // A sessão inválida deixa de aceitar escritas mesmo com o par ainda conectado.
            assertFalse(session.isConnected());

            byte[] command = ("CMD;1234567890;03;04;1\r").getBytes(StandardCharsets.US_ASCII);

            var error = assertThrows(
                    java.io.IOException.class,
                    () -> session.send(command)
            );
            assertEquals("DEVICE_DISCONNECTED", error.getMessage());
        }
    }


    /**
     * Cenário: socket simulado cuja escrita bloqueia além do prazo de produção. Ação: enviar
     * um byte e aguardar o temporizador fechar a saída. Resultado: a escrita falha, a sessão
     * fica desconectada e nenhuma nova escrita é aceita.
     */
    @Test
    void deadlineClosesBlockedWriterAndInvalidatesSession() {
        // close() do temporizador libera o latch; 8 s é a margem do teste, acima dos 5 s de produção.
        var closed = new java.util.concurrent.CountDownLatch(1);
        Socket stalled = new Socket() {
            /**
             * Simula a conexão local para que o teste alcance a escrita bloqueante.
             *
             * @return verdadeiro se a conexão está disponível localmente
             */
            @Override public boolean isConnected() { return true; }
            /**
             * Sinaliza o fechamento simulado para liberar a escrita bloqueada.
             */
            @Override public void close() { closed.countDown(); }
            /**
             * Fornece a saída controlada que permanece bloqueada até o fechamento.
             *
             * @return saída bloqueante simulada pelo teste
             */
            @Override public java.io.OutputStream getOutputStream() {
                return new java.io.OutputStream() {
                    /**
                     * Mantém a escrita suspensa até o temporizador fechar o socket simulado.
                     *
                     * @param value valor a interpretar
                     * @throws java.io.IOException se a escrita simulada for interrompida
                     */
                    @Override public void write(int value) throws java.io.IOException {
                        try {
                            if (!closed.await(8, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new AssertionError("write deadline did not close the socket");
                            }
                        } catch (InterruptedException e) {
                            throw new AssertionError(e);
                        }
                        throw new java.io.IOException("closed");
                    }
                };
            }
        };
        var session = new DeviceSession(UUID.randomUUID(), UUID.randomUUID(), "suntech", "1234567890", stalled);
        // O socket simulado mantém a escrita bloqueada até o prazo de produção fechar a saída.
        var error = assertThrows(java.io.IOException.class, () -> session.send(new byte[]{1}));
        // A falha de E/S invalida a sessão; o socket não deve ser reutilizado após envio incerto.
        assertEquals("closed", error.getMessage());
        assertFalse(session.isConnected());
    }
}
