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

/** Exercita a escrita no socket e a rejeição de novas escritas após desconexão ou invalidação. */
class DeviceSessionTcpTest {

    /**
     * Confere no outro extremo do socket os bytes enviados pela sessão.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldSendRawCommandOverTcp() throws Exception {

        try (ServerSocket server = new ServerSocket(0); Socket client = new Socket(
                "127.0.0.1",
                server.getLocalPort()
        ); Socket gatewaySocket = server.accept()) {

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
     * Rejeita um comando vazio antes da escrita.
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
            assertEquals("EMPTY_COMMAND_PAYLOAD", error.getMessage());
        }
    }

    /**
     * Rejeita a tentativa quando o socket está desconectado.
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
            try (Socket gatewaySocket = server.accept()) {
                session = new DeviceSession(UUID.randomUUID(), UUID.randomUUID(),
                        "suntech", "1234567890", gatewaySocket);
            }

            byte[] command = ("CMD;1234567890;03;04;1\r").getBytes(StandardCharsets.US_ASCII);

            var error = assertThrows(
                    java.io.IOException.class,
                    () -> session.send(command)
            );
            assertEquals("DEVICE_DISCONNECTED", error.getMessage());
        }
    }

    /**
     * Invalida a sessão e verifica a rejeição da escrita seguinte.
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

            assertTrue(session.isConnected());

            session.invalidate();

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
     * Bloqueia a saída até o temporizador fechar o socket e verifica a invalidação da sessão.
     */
    @Test
    void deadlineClosesBlockedWriterAndInvalidatesSession() {
        var closed = new java.util.concurrent.CountDownLatch(1);
        Socket stalled = new Socket() {
            /**
             * Simula a conexão local para que o teste alcance a escrita bloqueante.
             *
             * @return verdadeiro se a conexão está disponível localmente
             */
            @Override public boolean isConnected() { return true; }
            /**
             * Libera a escrita simulada ao fechar o socket.
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
                     * Mantém a escrita suspensa até o cenário simular o encerramento do socket.
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
        var error = assertThrows(java.io.IOException.class, () -> session.send(new byte[]{1}));
        assertEquals("closed", error.getMessage());
        assertFalse(session.isConnected());
    }
}
