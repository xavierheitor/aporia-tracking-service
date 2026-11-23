package software.aporia.tracking.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.aporia.tracking.core.ReceivedFrame;
import software.aporia.tracking.protocol.ProtocolAdapter;
import software.aporia.tracking.protocol.ProtocolFrame;
import software.aporia.tracking.protocol.ProtocolSession;
import software.aporia.tracking.storage.JdbcDeviceLookup;
import software.aporia.tracking.storage.JdbcPacketIngestion;
import software.aporia.tracking.storage.RegisteredDevice;

public final class TcpClientHandler {

    private static final Logger log
            = LoggerFactory.getLogger(TcpClientHandler.class);

    private static final int LOGIN_TIMEOUT_MS = 30_000;
    private static final int SESSION_IDLE_MS = sessionIdleMillis();

    private static int sessionIdleMillis() {
        int seconds = Integer.parseInt(System.getenv().getOrDefault("TRACKING_TCP_SESSION_IDLE_SECONDS", "180"));
        if (seconds <= 0) throw new IllegalArgumentException("INVALID_TCP_SESSION_IDLE_SECONDS");
        return Math.multiplyExact(seconds, 1000);
    }

    private final JdbcPacketIngestion ingestion;
    private final JdbcDeviceLookup deviceLookup;
    private final SessionRegistry sessionRegistry;
    private final java.util.function.IntFunction<ProtocolAdapter> selectAdapter;
    private final AtomicBoolean running;

    public TcpClientHandler(
            JdbcPacketIngestion ingestion,
            JdbcDeviceLookup deviceLookup,
            SessionRegistry sessionRegistry,
            ProtocolAdapter adapter,
            AtomicBoolean running
    ) {
        this(ingestion, deviceLookup, sessionRegistry, first -> adapter, running);
    }

    public TcpClientHandler(JdbcPacketIngestion ingestion, JdbcDeviceLookup deviceLookup,
                            SessionRegistry sessionRegistry, java.util.function.IntFunction<ProtocolAdapter> selectAdapter,
                            AtomicBoolean running) {
        this.ingestion = java.util.Objects.requireNonNull(ingestion);
        this.deviceLookup = java.util.Objects.requireNonNull(deviceLookup);
        this.sessionRegistry = java.util.Objects.requireNonNull(sessionRegistry);
        this.selectAdapter = java.util.Objects.requireNonNull(selectAdapter);
        this.running = java.util.Objects.requireNonNull(running);
    }

    public void handle(Socket client) {

        DeviceSession registeredSession = null;

        try (client; InputStream input = client.getInputStream()) {

            client.setSoTimeout(LOGIN_TIMEOUT_MS);

            byte[] buffer = new byte[4096];
            if (!running.get()) return;
            int bytesRead = input.read(buffer);
            if (bytesRead == -1) return;

            ProtocolAdapter adapter = selectAdapter.apply(Byte.toUnsignedInt(buffer[0]));
            ProtocolSession protocolSession = adapter.newSession();
            do {
                byte[] received = Arrays.copyOf(buffer, bytesRead);

                for (ProtocolFrame frame
                        : protocolSession.receive(received)) {

                    if (registeredSession != null) {

                        registeredSession.validateIdentity(
                                frame.deviceIdentity()
                        );

                        if (!sessionRegistry.isCurrent(registeredSession)) {
                            throw new IllegalStateException(
                                    "SESSION_REPLACED"
                            );
                        }

                    } else {
                        var found = deviceLookup.findEnabled(adapter.protocolId(), frame.deviceIdentity());
                        if (found.isEmpty()) {
                            if (frame.isLogin()) client.getOutputStream().write(protocolSession.rejectLogin());
                            throw new IllegalArgumentException("UNKNOWN_OR_DISABLED_DEVICE");
                        }
                        RegisteredDevice device = found.get();

                        client.setSoTimeout(SESSION_IDLE_MS);
                        registeredSession = new DeviceSession(
                                device.tenantId(),
                                device.deviceId(),
                                device.protocol(),
                                device.identity(),
                                client
                        );

                        var previous
                                = sessionRegistry.register(registeredSession);

                        if (previous.isPresent()) {

                            try {
                                previous.get().close();
                            } catch (IOException e) {
                                log.warn(
                                        "Failed to close replaced session",
                                        e
                                );
                            }
                        }

                        log.info(
                                "Device session registered: tenantId={}, deviceId={}",
                                device.tenantId(),
                                device.deviceId()
                        );
                    }

                    if (!sessionRegistry.isCurrent(registeredSession)) {
                        throw new IllegalStateException(
                                "SESSION_REPLACED"
                        );
                    }

                    if (frame.isLogin()) {
                        registeredSession.send(protocolSession.acknowledge(frame));
                        continue;
                    }
                    ReceivedFrame receivedFrame = new ReceivedFrame(
                            adapter.protocolId(),
                            frame.deviceIdentity(),
                            frame.payload(),
                            adapter.decoderVersion()
                    );

                    var packetId = ingestion.accept(receivedFrame, registeredSession.tenantId(), registeredSession.deviceId());

                    if (frame.acknowledgmentRequired()) registeredSession.send(protocolSession.acknowledge(frame));

                    log.debug(
                            "Packet persisted: id={}, protocol={}",
                            packetId,
                            adapter.protocolId()
                    );
                }
            } while (running.get() && (bytesRead = input.read(buffer)) != -1);

        } catch (SocketTimeoutException e) {
            log.debug("TCP session timed out");
        } catch (IllegalArgumentException e) {
            log.warn(
                    "Invalid protocol frame: {}",
                    e.getMessage()
            );
        } catch (IllegalStateException e) {

            if ("SESSION_REPLACED".equals(e.getMessage())) {
                log.info("Obsolete TCP session terminated");
            } else {
                log.error("TCP session processing failed", e);
            }

        } catch (IOException e) {

            if (!running.get() && e instanceof SocketException) {
                log.debug("TCP connection closed during shutdown");
            } else {
                log.warn("TCP connection error", e);
            }

        } finally {

            if (registeredSession != null) {
                sessionRegistry.unregister(registeredSession);
            }
        }
    }
}
