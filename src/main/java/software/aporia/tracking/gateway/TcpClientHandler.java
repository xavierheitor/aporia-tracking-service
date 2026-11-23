package software.aporia.tracking.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.aporia.tracking.core.ReceivedFrame;
import software.aporia.tracking.protocol.ProtocolAdapter;
import software.aporia.tracking.protocol.ProtocolFrame;
import software.aporia.tracking.protocol.ProtocolSession;
import software.aporia.tracking.storage.device.JdbcDeviceLookup;
import software.aporia.tracking.storage.device.RegisteredDevice;
import software.aporia.tracking.storage.packet.JdbcPacketIngestion;

/**
 * Conduz uma conexão da identificação do protocolo à descoberta do equipamento ou à ingestão dos
 * pacotes de uma sessão autorizada.
 */
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

    /** Restringe todas as conexões tratadas por esta instância ao adaptador fornecido. */
    public TcpClientHandler(
            JdbcPacketIngestion ingestion,
            JdbcDeviceLookup deviceLookup,
            SessionRegistry sessionRegistry,
            ProtocolAdapter adapter,
            AtomicBoolean running
    ) {
        this(ingestion, deviceLookup, sessionRegistry, first -> adapter, running);
    }

    /** Seleciona um adaptador pelo primeiro byte e mantém a escolha durante toda a conexão. */
    public TcpClientHandler(JdbcPacketIngestion ingestion, JdbcDeviceLookup deviceLookup,
                            SessionRegistry sessionRegistry, java.util.function.IntFunction<ProtocolAdapter> selectAdapter,
                            AtomicBoolean running) {
        this.ingestion = java.util.Objects.requireNonNull(ingestion);
        this.deviceLookup = java.util.Objects.requireNonNull(deviceLookup);
        this.sessionRegistry = java.util.Objects.requireNonNull(sessionRegistry);
        this.selectAdapter = java.util.Objects.requireNonNull(selectAdapter);
        this.running = java.util.Objects.requireNonNull(running);
    }

    /**
     * Consome leituras até o encerramento da conexão e remove condicionalmente a sessão
     * registrada na saída.
     */
    public void handle(Socket client) {

        DeviceSession registeredSession = null;

        try (client; InputStream input = client.getInputStream()) {

            long loginDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOGIN_TIMEOUT_MS);
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
                        var found = deviceLookup.find(adapter.protocolId(), frame.deviceIdentity());
                        if (found.isEmpty()) {
                            if (frame.isLogin()) {
                                // O login libera o primeiro lote para identificar o codec. A sessão de comandos depende da aprovação.
                                client.getOutputStream().write(protocolSession.acknowledge(frame));
                                continue;
                            }
                            deviceLookup.recordDiscovery(adapter.protocolId(), frame.deviceIdentity(), adapter.describe(frame));
                            return;
                        }
                        if (!found.get().enabled()) {
                            if (frame.isLogin()) client.getOutputStream().write(protocolSession.rejectLogin());
                            return;
                        }
                        RegisteredDevice device = found.get().device();

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

                    // A confirmação do lote só segue para o rastreador depois que accept retorna com o commit concluído.
                    if (frame.acknowledgmentRequired()) registeredSession.send(protocolSession.acknowledge(frame));

                    log.debug(
                            "Packet persisted: id={}, protocol={}",
                            packetId,
                            adapter.protocolId()
                    );
                }
                if (registeredSession == null) {
                    long remaining = loginDeadline - System.nanoTime();
                    if (remaining <= 0) return;
                    client.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
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
