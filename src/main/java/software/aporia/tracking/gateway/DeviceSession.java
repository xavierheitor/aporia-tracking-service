package software.aporia.tracking.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public final class DeviceSession {

    private final UUID tenantId;
    private final UUID deviceId;
    private final String protocol;
    private final String identity;

    private final Socket socket;

    private final ReentrantLock writeLock = new ReentrantLock();

    private volatile boolean active = true;

    public DeviceSession(
            UUID tenantId,
            UUID deviceId,
            String protocol,
            String identity,
            Socket socket
    ) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.deviceId = Objects.requireNonNull(deviceId);
        this.protocol = Objects.requireNonNull(protocol);
        this.identity = Objects.requireNonNull(identity);

        this.socket = Objects.requireNonNull(socket);

        if (protocol.isBlank()) {
            throw new IllegalArgumentException("INVALID_PROTOCOL");
        }
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID deviceId() {
        return deviceId;
    }

    public String protocol() {
        return protocol;
    }

    public boolean isConnected() {
        return active
                && socket.isConnected()
                && !socket.isClosed()
                && !socket.isOutputShutdown();
    }

    public void send(byte[] payload) throws IOException {

        Objects.requireNonNull(payload);

        if (payload.length == 0) {
            throw new IllegalArgumentException(
                    "EMPTY_COMMAND_PAYLOAD"
            );
        }

        writeLock.lock();

        try {
            if (!isConnected()) {
                throw new IOException("DEVICE_DISCONNECTED");
            }

            OutputStream output = socket.getOutputStream();
            output.write(payload);
            output.flush();

        } finally {
            writeLock.unlock();
        }
    }

    public String identity() {
        return identity;
    }

    public void validateIdentity(String receivedIdentity) {
        if (!identity.equals(receivedIdentity)) {
            throw new IllegalArgumentException(
                    "SESSION_IDENTITY_MISMATCH"
            );
        }
    }

    public void invalidate() {
        writeLock.lock();

        try {
            active = false;
        } finally {
            writeLock.unlock();
        }
    }

    public void close() throws IOException {
        invalidate();
        socket.close();
    }

}
