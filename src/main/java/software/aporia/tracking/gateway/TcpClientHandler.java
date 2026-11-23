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

    /** Registra eventos e falhas associados a esta classe. */
    private static final Logger log
            = LoggerFactory.getLogger(TcpClientHandler.class);

    /** Prazo em milissegundos para a fase anterior ao registro de sessão. */
    private static final int LOGIN_TIMEOUT_MS = 30_000;
    /** Tempo máximo de inatividade da sessão identificada, em milissegundos. */
    private static final int SESSION_IDLE_MS = sessionIdleMillis();

    /**
     * Converte em milissegundos o prazo configurado para inatividade de sessões identificadas.
     *
     * @return prazo de inatividade em milissegundos
     */
    private static int sessionIdleMillis() {
        int seconds = Integer.parseInt(System.getenv().getOrDefault("TRACKING_TCP_SESSION_IDLE_SECONDS", "180"));
        if (seconds <= 0) throw new IllegalArgumentException("INVALID_TCP_SESSION_IDLE_SECONDS");
        return Math.multiplyExact(seconds, 1000);
    }

    /** Persiste o pacote bruto antes da confirmação de recebimento. */
    private final JdbcPacketIngestion ingestion;
    /** Consulta o cadastro e registra a descoberta de identidades desconhecidas. */
    private final JdbcDeviceLookup deviceLookup;
    /** Registro que associa tenant e dispositivo à sessão atual. */
    private final SessionRegistry sessionRegistry;
    /** Seleciona o adaptador que conduzirá a conexão após a primeira leitura. */
    private final java.util.function.IntFunction<ProtocolAdapter> selectAdapter;
    /** Sinal de execução compartilhado com as tarefas do gateway. */
    private final AtomicBoolean running;

    /**
     * Restringe todas as conexões tratadas por esta instância ao adaptador fornecido.
     *
     * @param ingestion componente que persiste os quadros recebidos
     * @param deviceLookup consulta responsável pelo cadastro e pela descoberta
     * @param sessionRegistry registro de sessões ativas do gateway
     * @param adapter adaptador usado nas conexões deste manipulador
     * @param running indicador compartilhado de execução do serviço
     */
    public TcpClientHandler(
            JdbcPacketIngestion ingestion,
            JdbcDeviceLookup deviceLookup,
            SessionRegistry sessionRegistry,
            ProtocolAdapter adapter,
            AtomicBoolean running
    ) {
        this(ingestion, deviceLookup, sessionRegistry, first -> adapter, running);
    }

    /**
     * Seleciona um adaptador pelo primeiro byte e mantém a escolha durante toda a conexão.
     *
     * @param ingestion componente que persiste os quadros recebidos
     * @param deviceLookup consulta responsável pelo cadastro e pela descoberta
     * @param sessionRegistry registro de sessões ativas do gateway
     * @param selectAdapter função que escolhe o adaptador pelo primeiro byte
     * @param running indicador compartilhado de execução do serviço
     */
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
     *
     * @param client socket TCP da conexão do equipamento
     */
    public void handle(Socket client) {

        //Sessão registrada do dispositivo.
        DeviceSession registeredSession = null;

        try (client; InputStream input = client.getInputStream()) {

            //Prazo de login.
            long loginDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOGIN_TIMEOUT_MS);
            //Define o timeout de login.
            client.setSoTimeout(LOGIN_TIMEOUT_MS);

            //Buffer para leitura dos dados do cliente.
            byte[] buffer = new byte[4096];
            //Se o serviço não estiver em execução, sai da função.
            if (!running.get()) return;
            //Lê os dados do cliente.
            int bytesRead = input.read(buffer);
            //Se não houver dados, sai da função.
            if (bytesRead == -1) return;

            //Seleciona o adaptador.
            ProtocolAdapter adapter = selectAdapter.apply(Byte.toUnsignedInt(buffer[0]));
            //Cria a sessão do protocolo.
            ProtocolSession protocolSession = adapter.newSession();
            //Loop principal do tratamento da conexão.
            do {
                //Copia os dados lidos para o buffer.
                byte[] received = Arrays.copyOf(buffer, bytesRead);
                //Percorre os frames recebidos.
                for (ProtocolFrame frame
                        : protocolSession.receive(received)) {

                    //Se a sessão já estiver registrada, valida a identidade do dispositivo.
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
                        //Busca o dispositivo no banco de dados.
                        var found = deviceLookup.find(adapter.protocolId(), frame.deviceIdentity());
                        //Se o dispositivo não for encontrado, envia um ACK de login.
                        if (found.isEmpty()) {
                            if (frame.isLogin()) {
                                // O login libera o primeiro lote para identificar o codec. A sessão de comandos depende da aprovação.
                                //Envia um ACK de login.
                                client.getOutputStream().write(protocolSession.acknowledge(frame));
                                continue;
                            }
                            //Registra a descoberta do dispositivo no banco de dados.
                            deviceLookup.recordDiscovery(adapter.protocolId(), frame.deviceIdentity(), adapter.describe(frame));
                            return;
                        }

                        //Se o dispositivo não estiver habilitado, envia um ACK de login rejeitado.
                        if (!found.get().enabled()) {
                            if (frame.isLogin()) client.getOutputStream().write(protocolSession.rejectLogin());
                            return;
                        }

                        //Obtém o dispositivo registrado.
                        RegisteredDevice device = found.get().device();

                        //Define o timeout de inatividade da sessão.
                        client.setSoTimeout(SESSION_IDLE_MS);
                        //Cria a sessão do dispositivo.
                        registeredSession = new DeviceSession(
                                device.tenantId(),
                                device.deviceId(),
                                device.protocol(),
                                device.identity(),
                                client
                        );

                        //Registra a sessão do dispositivo.
                        var previous
                                = sessionRegistry.register(registeredSession);

                        //Se a sessão anterior estiver presente, fecha a sessão anterior.
                        if (previous.isPresent()) {

                            try {
                                //Fecha a sessão anterior.
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
                    //Se a sessão não estiver atual, lança uma exceção.
                    if (!sessionRegistry.isCurrent(registeredSession)) {
                        throw new IllegalStateException(
                                "SESSION_REPLACED"
                        );
                    }

                    //Se o frame for um login, envia um ACK de login e continua o loop.
                    if (frame.isLogin()) {
                        registeredSession.send(protocolSession.acknowledge(frame));
                        continue;
                    }
                    //Cria o frame recebido.
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
                //Se a sessão não estiver registrada, verifica se o prazo de login expirou.
                if (registeredSession == null) {
                    //Obtém o tempo restante de login.
                    long remaining = loginDeadline - System.nanoTime();
                    //Se o prazo de login expirou, sai da função.
                    if (remaining <= 0) return;
                    //Define o timeout de login.
                    client.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                }
                //Continua o loop.
            } while (running.get() && (bytesRead = input.read(buffer)) != -1);

        } catch (SocketTimeoutException e) {
            //Se o timeout de login expirou, avisa.
            log.debug("TCP session timed out");
        //Se o frame for inválido, avisa.
        } catch (IllegalArgumentException e) {
            //Se o frame for inválido, avisa.
            log.warn(
                    "Invalid protocol frame: {}",
                    e.getMessage()
            );
        } catch (IllegalStateException e) {
            //Se a sessão foi substituída, avisa.
            if ("SESSION_REPLACED".equals(e.getMessage())) {
                log.info("Obsolete TCP session terminated");
            } else {
                //Se a sessão não foi substituída, avisa.
                log.error("TCP session processing failed", e);
            }

        } catch (IOException e) {
            //Se o serviço não estiver em execução e o erro for de socket, avisa.
            if (!running.get() && e instanceof SocketException) {
                //Se o serviço não estiver em execução e o erro for de socket, avisa.
                log.debug("TCP connection closed during shutdown");
            //Se o serviço estiver em execução e o erro for de socket, avisa.
            } else {
                log.warn("TCP connection error", e);
            }

        } finally {

            //Se a sessão estiver registrada, desregistra a sessão.
            if (registeredSession != null) {
                //Desregistra a sessão.
                sessionRegistry.unregister(registeredSession);
            }
        }
    }
}
