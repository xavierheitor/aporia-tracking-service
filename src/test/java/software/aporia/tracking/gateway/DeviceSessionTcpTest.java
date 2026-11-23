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

class DeviceSessionTcpTest {

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


    @Test
    void deadlineClosesBlockedWriterAndInvalidatesSession() {
        var closed = new java.util.concurrent.CountDownLatch(1);
        Socket stalled = new Socket() {
            @Override public boolean isConnected() { return true; }
            @Override public void close() { closed.countDown(); }
            @Override public java.io.OutputStream getOutputStream() {
                return new java.io.OutputStream() {
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
