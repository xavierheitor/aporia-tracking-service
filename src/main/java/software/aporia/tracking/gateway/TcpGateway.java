package software.aporia.tracking.gateway;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.aporia.tracking.command.dispatcher.CommandDispatcher;
import software.aporia.tracking.command.dispatcher.DispatchOwner;
import software.aporia.tracking.protocol.ProtocolAdapter;
import software.aporia.tracking.protocol.ProtocolRegistry;
import software.aporia.tracking.protocol.suntech.SuntechAdapter;
import software.aporia.tracking.protocol.teltonika.TeltonikaAdapter;
import software.aporia.tracking.storage.Database;
import software.aporia.tracking.storage.command.DeviceCommandDispatchRepository;
import software.aporia.tracking.storage.device.JdbcDeviceLookup;
import software.aporia.tracking.storage.packet.JdbcPacketIngestion;

/**
 * Inicia o servidor TCP, distribui conexões entre tarefas e, quando
 * configurado, executa o dispatcher junto às sessões.
 */
public class TcpGateway {

    //Logger para registrar eventos e falhas associados a esta classe.
    private static final Logger log
            = LoggerFactory.getLogger(TcpGateway.class);

    //Porta configurada para o listener TCP.
    private static final int PORT = Integer.parseInt(
            System.getenv().getOrDefault("TRACKING_TCP_PORT", "5500")
    );

    //Tempo máximo de espera usado durante o encerramento do gateway.
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;

    /**
     * Abre o listener, instala a rotina de encerramento e inicia o envio
     * periódico se houver proprietário configurado.
     *
     * @param args argumentos da linha de comando, atualmente ignorados
     * @throws IOException se a operação de entrada ou saída falhar
     */
    public static void main(String[] args) throws IOException {

        //Indicador compartilhado de execução do serviço.
        AtomicBoolean running = new AtomicBoolean(true);
        //Sinal de conclusão do laço do gateway.
        CountDownLatch gatewayFinished = new CountDownLatch(1);

        //Conjunto de sockets ativos.
        Set<Socket> activeClients
                = ConcurrentHashMap.newKeySet();

        //Caminho do arquivo de proprietário.
        String ownerPath = System.getenv("TRACKING_DISPATCH_OWNER_FILE");
        //Cria o DataSource para a conexão com o banco de dados.
        try (
                var dataSource = Database.createDataSource(); //Cria o ServerSocket para o listener TCP.
                 ServerSocket server = new ServerSocket(
                        PORT,
                        50,
                        InetAddress.getByName(System.getenv().getOrDefault("TRACKING_TCP_BIND", "0.0.0.0"))
                ); //Cria o executor para as tarefas virtuais.
                 var executor = Executors.newVirtualThreadPerTaskExecutor(); //Abre o arquivo de proprietário.
                 var owner = ownerPath == null ? null : DispatchOwner.open(Path.of(ownerPath)); //Cria o executor para as tarefas agendadas.
                 var polling = Executors.newSingleThreadScheduledExecutor()) {
             
            //se o datasource for criado, segue com a inicialização do gateway.

            //Cria o JdbcPacketIngestion para o ingestão de pacotes.
            JdbcPacketIngestion ingestion
                    = new JdbcPacketIngestion(dataSource);

            //Cria o JdbcDeviceLookup para a busca de dispositivos.
            JdbcDeviceLookup deviceLookup
                    = new JdbcDeviceLookup(dataSource);

            //Cria o SessionRegistry para o registro de sessões.
            SessionRegistry sessionRegistry
                    = new SessionRegistry();

            //Cria o ProtocolRegistry para o registro de protocolos.
            ProtocolRegistry registry = new ProtocolRegistry(
                    List.of(new SuntechAdapter(), new TeltonikaAdapter())
            );

            //Obtém o identificador do protocolo configurado.
            String protocolId = System.getenv()
                    .getOrDefault("TRACKING_PROTOCOL", "auto");

            //Cria a função para selecionar o protocolo.
            java.util.function.IntFunction<ProtocolAdapter> select = registry::detect;
            //Se o identificador do protocolo não for "auto", seleciona o protocolo fixo.
            if (!"auto".equals(protocolId)) {
                var fixed = registry.get(protocolId);
                select = first -> fixed;
            }

            //Cria o TcpClientHandler para o tratamento de clientes.
            //responsável por tratar a conexão do cliente e o protocolo do dispositivo.
            TcpClientHandler clientHandler = new TcpClientHandler(
                    ingestion,
                    deviceLookup,
                    sessionRegistry,
                    select,
                    running
            );

            log.info("TCP listener configured for {}", protocolId);
            //Se o proprietário não for nulo, cria o CommandDispatcher para o envio de comandos.
            if (owner != null) {
                //Cria o CommandDispatcher para o envio de comandos.
                var dispatcher = new CommandDispatcher(
                        dataSource, new DeviceCommandDispatchRepository(),
                        new SessionCommandTransport(sessionRegistry), owner);
                // O intervalo começa após cada rodada; uma rodada lenta não sobrepõe outra no mesmo emissor.
                polling.scheduleWithFixedDelay(() -> poll(dispatcher, sessionRegistry, running),
                        0, 1, TimeUnit.SECONDS);
            }
            //Cria o Thread para o encerramento do gateway.
            Thread shutdownHook = new Thread(
                    () -> shutdown(server, running, gatewayFinished, activeClients),
                    "tracking-gateway-shutdown");

            //Adiciona o Thread para o encerramento do gateway.
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            try {

                log.info(
                        "Tracking TCP Gateway listening on {}:{}",
                        server.getInetAddress().getHostAddress(), server.getLocalPort()
                );

                //Loop principal do gateway.
                while (running.get()) {

                    try {
                        //Aceita uma nova conexão.
                        Socket client = server.accept();

                        //Se o serviço não estiver em execução, fecha o socket e sai do loop.
                        if (!running.get()) {
                            client.close();
                            break;
                        }

                        //Adiciona o socket à lista de sockets ativos.
                        activeClients.add(client);

                        try {
                            //Envia a conexão para o TcpClientHandler para tratamento.
                            executor.submit(() -> {
                                try {
                                    //Trata a conexão.
                                    clientHandler.handle(client);
                                } finally {
                                    //Remove o socket da lista de sockets ativos.
                                    activeClients.remove(client);
                                }
                            });

                        } catch (RuntimeException e) {
                            //Remove o socket da lista de sockets ativos.
                            activeClients.remove(client);
                            //Fecha o socket.
                            client.close();
                            throw e;
                        }

                    } catch (SocketException e) {
                        //Se o serviço não estiver em execução, sai do loop.
                        if (!running.get()) {
                            break;
                        }
                        throw e;
                    }
                }

            } finally {

                log.info("TCP Gateway stopping");

                // Desbloqueia as leituras TCP das sessões ativas.
                for (Socket client : activeClients) {
                    try {
                        client.close();
                    } catch (IOException e) {
                        log.debug(
                                "Error closing client socket",
                                e
                        );
                    }
                }
            }
            // O try-with-resources aguarda as virtual threads
            // antes de fechar o DataSource.
        } finally {
            gatewayFinished.countDown();
            log.info("TCP Gateway stopped");
        }
    }

    /**
     * Interrompe a aceitação de conexões, fecha sockets ativos e coordena a
     * saída das tarefas do gateway.
     *
     * @param server socket de escuta do gateway
     * @param running indicador compartilhado de execução do serviço
     * @param gatewayFinished sinal de conclusão do laço do gateway
     * @param activeClients conexões aceitas e ainda acompanhadas pelo gateway
     */
    private static void shutdown(ServerSocket server, AtomicBoolean running,
            CountDownLatch gatewayFinished, Set<Socket> activeClients) {
        log.info("Shutdown requested for TCP Gateway");
        //Define o indicador de execução do serviço como false.
        running.set(false);

        try {
            //Fecha o socket de escuta do gateway.
            server.close();
        } catch (IOException e) {
            log.warn("Error closing TCP listener", e);
        }

        //Tenta aguardar a conclusão das tarefas do gateway.
        try {
            //Se o gateway não foi concluído dentro do tempo limite, avisa e fecha os sockets ativos.
            if (!gatewayFinished.await(
                    SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            )) {
                log.warn("Gateway shutdown timeout exceeded");

                //Fecha os sockets ativos.
                for (Socket client : activeClients) {
                    try {
                        //Fecha o socket.
                        client.close();
                    } catch (IOException e) {
                        log.debug(
                                "Error closing client socket",
                                e
                        );
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Executa a recuperação e consulta a fila de comandos para os dispositivos
     * com sessão registrada.
     *
     * @param dispatcher coordenador do envio de comandos
     * @param sessions registro de sessões ativas do gateway
     * @param running indicador compartilhado de execução do serviço
     */
    private static void poll(CommandDispatcher dispatcher,
            SessionRegistry sessions, AtomicBoolean running) {
        //Se o serviço não estiver em execução, sai da função.
        if (!running.get()) {
            return;
        }
        //Tenta recuperar e enviar os comandos.
        try {
            //Recupera e envia os comandos.
            dispatcher.recover();
            //Percorre as sessões online.
            for (DeviceSession session : sessions.onlineSessions()) {
                //Se o serviço não estiver em execução, sai do loop.
                if (!running.get()) {
                    break;
                }
                //Envia o próximo comando.
                dispatcher.dispatchNext(session.tenantId(), session.deviceId());
            }
        } catch (java.sql.SQLException error) {
            log.error("Command polling failed, SQLState={}", error.getSQLState());
        } catch (RuntimeException error) {
            log.error("Command polling failed", error);
        }
    }
}
