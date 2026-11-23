package software.aporia.tracking.command.dispatcher;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import software.aporia.tracking.gateway.DeviceSession;
import software.aporia.tracking.gateway.SessionRegistry;

class SessionCommandTransportTest {

    private static final String PROTOCOL = "suntech";
    private static final String IDENTITY = "1234567890";

    @Test
    void shouldReportDisconnectedWhenSessionDoesNotExist() {

        SessionRegistry registry = new SessionRegistry();

        SessionCommandTransport transport
                = new SessionCommandTransport(registry);

        assertFalse(
                transport.isConnected(
                        UUID.randomUUID(),
                        UUID.randomUUID()
                )
        );
    }

    @Test
    void shouldRejectSendingWithoutSession() {

        SessionRegistry registry = new SessionRegistry();

        SessionCommandTransport transport
                = new SessionCommandTransport(registry);

        assertThrows(
                IOException.class,
                () -> transport.send(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "TEST\r".getBytes(
                                StandardCharsets.US_ASCII
                        )
                )
        );
    }

    @Test
    void shouldSendExactBytesToConnectedDevice()
            throws Exception {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        byte[] payload = ("CMD;" + IDENTITY + ";03;04;1\r").getBytes(StandardCharsets.US_ASCII);

        try (ServerSocket server = new ServerSocket(0); Socket client = new Socket(
                "127.0.0.1",
                server.getLocalPort()
        ); Socket gatewaySocket = server.accept()) {

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

            assertFalse(
                    transport.isConnected(tenantId, deviceId)
            );
        }
    }

    @Test
    void shouldRejectInvalidatedSession()
            throws Exception {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        try (ServerSocket server = new ServerSocket(0); Socket client = new Socket(
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

            SessionCommandTransport transport
                    = new SessionCommandTransport(registry);

            assertFalse(
                    transport.isConnected(tenantId, deviceId)
            );

            assertThrows(
                    IOException.class,
                    () -> transport.send(
                            tenantId,
                            deviceId,
                            "TEST\r".getBytes(
                                    StandardCharsets.US_ASCII
                            )
                    )
            );
        }
    }

    @Test
    void shouldRejectCrossTenantAccess()
            throws Exception {

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        try (ServerSocket server = new ServerSocket(0); Socket client = new Socket(
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

            assertFalse(
                    transport.isConnected(
                            anotherTenant,
                            deviceId
                    )
            );

            assertThrows(
                    IOException.class,
                    () -> transport.send(
                            anotherTenant,
                            deviceId,
                            "TEST\r".getBytes(
                                    StandardCharsets.US_ASCII
                            )
                    )
            );
        }
    }
}
