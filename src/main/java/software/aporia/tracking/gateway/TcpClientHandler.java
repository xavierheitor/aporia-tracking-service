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

    private static final int READ_TIMEOUT_MS = 30_000;

    private final JdbcPacketIngestion ingestion;
    private final JdbcDeviceLookup deviceLookup;
    private final SessionRegistry sessionRegistry;
    private final ProtocolAdapter adapter;
    private final AtomicBoolean running;

    public TcpClientHandler(
            JdbcPacketIngestion ingestion,
            JdbcDeviceLookup deviceLookup,
            SessionRegistry sessionRegistry,
            ProtocolAdapter adapter,
            AtomicBoolean running
    ) {
        this.ingestion = java.util.Objects.requireNonNull(ingestion);
        this.deviceLookup = java.util.Objects.requireNonNull(deviceLookup);
        this.sessionRegistry = java.util.Objects.requireNonNull(sessionRegistry);
        this.adapter = java.util.Objects.requireNonNull(adapter);
        this.running = java.util.Objects.requireNonNull(running);
    }

    public void handle(Socket client) {

        ProtocolSession protocolSession = adapter.newSession();
        DeviceSession registeredSession = null;

        try (client; InputStream input = client.getInputStream()) {

            client.setSoTimeout(READ_TIMEOUT_MS);

            byte[] buffer = new byte[4096];
            int bytesRead;

            while (running.get()
                    && (bytesRead = input.read(buffer)) != -1) {

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
                        RegisteredDevice device
                                = deviceLookup.findEnabled(
                                        adapter.protocolId(),
                                        frame.deviceIdentity()
                                ).orElseThrow(()
                                        -> new IllegalArgumentException(
                                                "UNKNOWN_OR_DISABLED_DEVICE"
                                        )
                                );

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

                    ReceivedFrame receivedFrame = new ReceivedFrame(
                            adapter.protocolId(),
                            frame.deviceIdentity(),
                            frame.payload(),
                            adapter.decoderVersion()
                    );

                    var packetId = ingestion.accept(receivedFrame, registeredSession.tenantId(), registeredSession.deviceId());

                    log.debug(
                            "Packet persisted: id={}, protocol={}",
                            packetId,
                            adapter.protocolId()
                    );
                }
            }

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
