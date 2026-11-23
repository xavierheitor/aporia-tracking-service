package software.aporia.tracking.gateway;

import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import software.aporia.tracking.command.dispatcher.CommandTransport.Endpoint;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public final class DeviceSession implements Endpoint {

    private static final int WRITE_TIMEOUT_SECONDS = 5;
    private static final ScheduledThreadPoolExecutor deadlines;
    static {
        deadlines = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("tcp-write-deadline").factory());
        deadlines.setRemoveOnCancelPolicy(true);
    }

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

    @Override
    public String protocol() {
        return protocol;
    }

    public boolean isConnected() {
        return active
                && socket.isConnected()
                && !socket.isClosed()
                && !socket.isOutputShutdown();
    }

    @Override
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

            var writing = new AtomicBoolean(true);
            var deadline = deadlines.schedule(() -> {
                if (writing.compareAndSet(true, false)) abortSocket();
            }, WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try {
                OutputStream output = socket.getOutputStream();
                output.write(payload);
                output.flush();
                if (!writing.compareAndSet(true, false)) throw new IOException("TCP_WRITE_TIMEOUT");
            } catch (IOException error) {
                abortSocket();
                throw error;
            } finally {
                writing.set(false);
                deadline.cancel(false);
            }

        } finally {
            writeLock.unlock();
        }
    }

    private void abortSocket() {
        active = false;
        try {
            socket.close();
        } catch (IOException error) {
            LoggerFactory.getLogger(DeviceSession.class).warn("Failed to close command socket", error);
        }
    }

    @Override
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

    @SuppressWarnings("ConvertToTryWithResources") // O socket pertence à sessão até o fechamento explícito.
    public void close() throws IOException {
        invalidate();
        socket.close();
    }

}
