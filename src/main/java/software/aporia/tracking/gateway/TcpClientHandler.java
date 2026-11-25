package software.aporia.tracking.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.aporia.tracking.core.ReceivedFrame;
import software.aporia.tracking.protocol.ProtocolAdapter;
import software.aporia.tracking.protocol.ProtocolFrame;
import software.aporia.tracking.protocol.ProtocolSession;
import software.aporia.tracking.storage.device.JdbcDeviceLookup;
import software.aporia.tracking.storage.device.RegisteredDevice;
import software.aporia.tracking.storage.packet.JdbcPacketIngestion;

import software.aporia.tracking.observability.metrics.GatewayMetrics;

/**
 * Conduz uma conexão da identificação do protocolo à descoberta do equipamento ou à ingestão dos pacotes de uma sessão
 * autorizada.
 *
 * <p>
 * {@link #handle(Socket)} mantém o estado de uma única conexão e orquestra:
 * </p>
 * <ol>
 * <li>Aplica o prazo de login e seleciona o adaptador pelo primeiro byte recebido. A sessão de protocolo recompõe os
 * quadros entre as leituras TCP.</li>
 * <li>Consulta o cadastro até admitir um equipamento habilitado. Um login desconhecido pode ser confirmado para
 * permitir identificar o primeiro lote, mas não cria sessão autorizada; o primeiro quadro de dados gera a descoberta e
 * encerra a conexão. Cadastros desabilitados são recusados.</li>
 * <li>{@code registerSession()} ativa o token no banco e publica a sessão local, fechando a conexão substituída. A
 * conexão admitida passa ao timeout de inatividade.</li>
 * <li>{@code processAuthorizedFrame()} valida identidade e sessão atual, confirma logins e persiste dados antes de
 * enviar o ACK correspondente.</li>
 * <li>Enquanto não houver admissão, {@code updateLoginTimeout()} mantém o prazo total de login, mesmo com leituras
 * fragmentadas. Ao sair por EOF, parada ou falha, fecha o socket e remove somente a própria sessão do registro.</li>
 * </ol>
 *
 * <p>
 * A instância pode atender várias conexões: adaptador, sessão de protocolo, buffer e sessão registrada são locais a
 * cada chamada de {@code handle()}.
 * </p>
 */
public final class TcpClientHandler {

    // Registra eventos e falhas associados a esta classe.
    private static final Logger log = LoggerFactory.getLogger(TcpClientHandler.class);

    // Prazo em milissegundos para a fase anterior ao registro de sessão.
    private static final int LOGIN_TIMEOUT_MS = 30_000;

    // Tempo máximo de inatividade da sessão identificada, em milissegundos.
    private static final int SESSION_IDLE_MS = sessionIdleMillis();

    // Metricas e monitoramento
    private final GatewayMetrics metrics;

    // Persiste o pacote bruto antes da confirmação de recebimento.
    private final JdbcPacketIngestion ingestion;
    private final JdbcDeviceLookup deviceLookup;
    private final SessionRegistry sessionRegistry;
    private final java.util.function.IntFunction<ProtocolAdapter> selectAdapter;

    private final AtomicBoolean running;

    public TcpClientHandler(
            JdbcPacketIngestion ingestion,
            JdbcDeviceLookup deviceLookup,
            SessionRegistry sessionRegistry,
            IntFunction<ProtocolAdapter> selectAdapter,
            AtomicBoolean running,
            GatewayMetrics metrics) {

        this.ingestion = Objects.requireNonNull(ingestion);
        this.deviceLookup = Objects.requireNonNull(deviceLookup);
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry);
        this.selectAdapter = Objects.requireNonNull(selectAdapter);
        this.running = Objects.requireNonNull(running);
        this.metrics = Objects.requireNonNull(metrics);
    }

    /**
     * Consome leituras até o encerramento da conexão e remove condicionalmente a sessão registrada na saída.
     *
     * @param client socket TCP da conexão do equipamento
     */
    public void handle(Socket client) {

        DeviceSession registeredSession = null;
        try (
                client;
                InputStream input = client.getInputStream()
        ) {
            long loginDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOGIN_TIMEOUT_MS);
            client.setSoTimeout(LOGIN_TIMEOUT_MS);
            byte[] buffer = new byte[4096];
            if (!running.get())
                return;
            int bytesRead = input.read(buffer);
            if (bytesRead == -1)
                return;

            ProtocolAdapter adapter = selectAdapter.apply(Byte.toUnsignedInt(buffer[0]));
            ProtocolSession protocolSession = adapter.newSession();
            do {
                metrics.bytesReceived(bytesRead);
                for (ProtocolFrame frame : protocolSession.receive(Arrays.copyOf(buffer, bytesRead))) {
                    if (registeredSession == null) {
                        var found = deviceLookup.find(adapter.protocolId(), frame.deviceIdentity());
                        if (found.isEmpty()) {
                            if (frame.isLogin()) {
                                // Libera o primeiro lote para descoberta do codec, sem autorizar comandos.
                                client.getOutputStream().write(protocolSession.acknowledge(frame));
                                continue;
                            }
                            deviceLookup.recordDiscovery(
                                    adapter.protocolId(),
                                    frame.deviceIdentity(),
                                    adapter.describe(frame));
                            return;
                        }
                        if (!found.get().enabled()) {
                            if (frame.isLogin()) {
                                client.getOutputStream().write(protocolSession.rejectLogin());
                            }
                            return;
                        }
                        registeredSession = registerSession(client, found.get().device());
                    }
                    processAuthorizedFrame(adapter, protocolSession, registeredSession, frame);
                }
                if (registeredSession == null && !updateLoginTimeout(client, loginDeadline))
                    return;
            } while (running.get() && (bytesRead = input.read(buffer)) != -1);
        } catch (SocketTimeoutException e) {
            log.debug("TCP session timed out");
        } catch (IllegalArgumentException e) {
            metrics.protocolError();
            log.warn("Invalid protocol frame: {}", e.getMessage());
        } catch (IllegalStateException e) {
            logProcessingFailure(e);
        } catch (IOException e) {
            if (!running.get() && e instanceof SocketException) {
                log.debug("TCP connection closed during shutdown");
            } else {
                log.warn("TCP connection error", e);
            }
        } finally {
            if (registeredSession != null) {
                // A remoção condicional preserva uma sessão nova admitida durante a reconexão.
                sessionRegistry.unregister(registeredSession);
            }
        }
    }

    /**
     * Ativa o token no banco antes de publicar a sessão local. Se a ativação falhar, o registro preserva a sessão
     * anterior e a exceção encerra a conexão.
     *
     * @param client socket do equipamento habilitado
     * @param device cadastro autorizado para esta conexão
     * @return sessão publicada no registro compartilhado
     * @throws IOException se não for possível configurar o timeout do socket
     */
    private DeviceSession registerSession(Socket client, RegisteredDevice device) throws IOException {
        client.setSoTimeout(SESSION_IDLE_MS);
        var session = new DeviceSession(
                device.tenantId(),
                device.deviceId(),
                device.protocol(),
                device.identity(),
                client);
        var previous = sessionRegistry.register(
                session,
                () -> deviceLookup.activateSession(device, session.sessionToken()));
        if (previous.isPresent()) {
            metrics.deviceReconnected(device.protocol());
            try {
                previous.get().close();
            } catch (IOException e) {
                log.warn("Failed to close replaced session", e);
            }
        }
        log.info("Device session registered: tenantId={}, deviceId={}", device.tenantId(), device.deviceId());
        return session;
    }

    /**
     * Valida o vínculo do quadro e persiste dados no tenant e dispositivo admitidos. O ACK de dados só é enviado depois
     * que a ingestão retorna com o commit concluído.
     *
     * @param adapter         adaptador escolhido para a conexão
     * @param protocolSession estado do protocolo usado para gerar as confirmações
     * @param session         sessão autorizada do equipamento
     * @param frame           quadro recomposto pela sessão de protocolo
     * @throws IOException se a escrita da confirmação falhar
     */
    private void processAuthorizedFrame(
            ProtocolAdapter adapter,
            ProtocolSession protocolSession,
            DeviceSession session,
            ProtocolFrame frame) throws IOException {
        session.validateIdentity(frame.deviceIdentity());
        if (!sessionRegistry.isCurrent(session)) {
            throw new IllegalStateException("SESSION_REPLACED");
        }
        if (frame.isLogin()) {
            session.send(protocolSession.acknowledge(frame));
            return;
        }
        var receivedFrame = new ReceivedFrame(
                adapter.protocolId(),
                frame.deviceIdentity(),
                frame.payload(),
                adapter.decoderVersion());
        long startedAt = System.nanoTime();

        java.util.UUID packetId;

        try {
            packetId = ingestion.accept(
                    receivedFrame,
                    session.tenantId(),
                    session.deviceId());

            metrics.packetIngested(adapter.protocolId());

        } catch (RuntimeException error) {
            metrics.ingestionFailed(adapter.protocolId());

            throw error;

        } finally {
            metrics.recordIngestionDuration(
                    adapter.protocolId(),
                    System.nanoTime() - startedAt);
        }
        if (frame.acknowledgmentRequired()) {
            session.send(protocolSession.acknowledge(frame));
        }
        log.debug("Packet persisted: id={}, protocol={}", packetId, adapter.protocolId());
    }

    /**
     * Ajusta a próxima leitura ao prazo total de login, sem renová-lo por fragmento recebido.
     *
     * @param client   socket ainda sem sessão autorizada
     * @param deadline instante limite medido pelo relógio monotônico
     * @return falso quando o prazo já terminou
     * @throws IOException se a configuração do timeout falhar
     */
    private boolean updateLoginTimeout(Socket client, long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0)
            return false;
        client.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
        return true;
    }

    /** Distingue encerramentos esperados de falhas que precisam de diagnóstico. */
    private void logProcessingFailure(IllegalStateException error) {
        if (null == error.getMessage()) {
            log.error("TCP session processing failed", error);
        } else
            switch (error.getMessage()) {
                case "SESSION_REPLACED" -> log.info("Obsolete TCP session terminated");
                case "SESSION_ADMISSION_DEFERRED" -> log.info("Device session admission deferred");
                default -> log.error("TCP session processing failed", error);
            }
    }

    /**
     * Converte em milissegundos o prazo configurado para inatividade de sessões identificadas.
     *
     * @return prazo de inatividade em milissegundos
     */
    private static int sessionIdleMillis() {
        int seconds = Integer.parseInt(System.getenv().getOrDefault("TRACKING_TCP_SESSION_IDLE_SECONDS", "180"));
        if (seconds <= 0) {
            throw new IllegalArgumentException("INVALID_TCP_SESSION_IDLE_SECONDS");
        }
        return Math.multiplyExact(seconds, 1000);
    }
}
