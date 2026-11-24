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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;

import javax.sql.DataSource;

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
 * Mantém o listener TCP do gateway e atende cada conexão em uma thread virtual.
 * O {@link TcpClientHandler} registra as sessões identificadas. Quando o envio
 * de comandos está habilitado, o dispatcher consulta esse mesmo registro.
 *
 * <p>
 * O {@link #main(String[])} orquestra o fluxo nesta ordem:
 * </p>
 * <ol>
 * <li>Abre o pool JDBC, o listener com {@code openListener()}, o executor de
 * threads virtuais, o proprietário com {@code openDispatchOwner()} e o executor
 * de polling. O proprietário lê ou cria um UUID em arquivo persistente e mantém
 * seu bloqueio exclusivo para identificar e recuperar tentativas deste
 * emissor.</li>
 * <li>Cria o {@link SessionRegistry} compartilhado e chama
 * {@code createClientHandler()}. Este usa {@code protocolSelector()} para
 * escolher
 * entre detecção pelo primeiro byte e protocolo fixo configurado.</li>
 * <li>Se houver {@code TRACKING_DISPATCH_OWNER_FILE}, chama
 * {@code startCommandPolling()}. Cada rodada de {@code poll()} recupera estados
 * pendentes e tenta enviar comandos às sessões locais identificadas. A primeira
 * rodada é imediata; as seguintes começam um segundo após a anterior terminar.
 * Sem essa variável, o atendimento TCP funciona sem o polling de comandos.</li>
 * <li>Registra o hook de encerramento e entra em {@code acceptClients()}.
 * Cada socket aceito passa por {@code submitClient()}, que o acompanha no
 * conjunto
 * de clientes ativos e executa {@code TcpClientHandler.handle()} em uma thread
 * virtual. O handler trata o protocolo e publica as sessões identificadas no
 * registro também consultado pelo dispatcher.</li>
 * <li>No encerramento, {@code shutdown()} sinaliza a parada e fecha o listener
 * para desbloquear a aceitação. Ao sair desse laço, o {@code main} chama
 * {@code closeActiveClients()} e fecha os recursos em ordem inversa: polling,
 * proprietário, executor TCP, listener e pool JDBC. Assim, o bloqueio do
 * emissor
 * permanece até o polling terminar e o banco fica disponível enquanto as
 * tarefas
 * TCP encerram. Por fim, libera {@code gatewayFinished}; se a espera do hook
 * exceder o prazo, ele também tenta fechar os clientes ativos.</li>
 * </ol>
 */
public class TcpGateway {

    private static final Logger log = LoggerFactory.getLogger(TcpGateway.class);
    private static final int PORT = Integer.parseInt(
            System.getenv().getOrDefault("TRACKING_TCP_PORT", "5500"));
    /**
     * Tempo que o hook aguarda antes de tentar fechar os sockets ainda ativos.
     */
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;

    /**
     * Abre os recursos do gateway e aceita conexões até o encerramento. A
     * variável {@code TRACKING_PROTOCOL} define a seleção de protocolo e
     * {@code TRACKING_DISPATCH_OWNER_FILE} habilita o envio periódico de
     * comandos.
     *
     * @param args argumentos da linha de comando, atualmente ignorados
     * @throws IOException se falhar a abertura do listener, o acesso ao arquivo
     *                     do emissor ou uma operação de socket no laço de aceitação
     */
    public static void main(String[] args) throws IOException {
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch gatewayFinished = new CountDownLatch(1);
        Set<Socket> activeClients = ConcurrentHashMap.newKeySet();

        // Os recursos fecham em ordem inversa. O emissor mantém o bloqueio até o
        // polling terminar e o pool JDBC continua disponível enquanto as tarefas TCP
        // encerram suas sessões.
        try (var dataSource = Database.createDataSource();
                var server = openListener();
                var executor = Executors.newVirtualThreadPerTaskExecutor();
                var owner = openDispatchOwner();
                var polling = Executors.newSingleThreadScheduledExecutor()) {
            var sessions = new SessionRegistry();
            var handler = createClientHandler(dataSource, sessions, running);
            if (owner != null) {
                startCommandPolling(polling, dataSource, sessions, owner, running);
            }

            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> shutdown(server, running, gatewayFinished, activeClients),
                    "tracking-gateway-shutdown"));

            // aceita os clientes
            try {
                acceptClients(server, executor, handler, running, activeClients);
            } finally {
                log.info("TCP Gateway stopping");
                closeActiveClients(activeClients);
            }
        } finally {
            // Libera o hook após a tentativa de fechamento de todos os recursos.
            gatewayFinished.countDown();
            log.info("TCP Gateway stopped");
        }
    }

    /**
     * Abre o listener no endereço e na porta configurados para este processo.
     *
     * @return socket de escuta que o gateway deve fechar ao encerrar
     * @throws IOException se falhar a resolução do endereço ou a abertura da
     *                     porta
     */
    private static ServerSocket openListener() throws IOException {
        String bindAddress = System.getenv().getOrDefault("TRACKING_TCP_BIND", "0.0.0.0");
        return new ServerSocket(PORT, 50, InetAddress.getByName(bindAddress));
    }

    /**
     * Obtém o bloqueio exclusivo do arquivo que identifica o emissor de
     * comandos. O UUID persistido identifica as tentativas deste emissor; o
     * bloqueio impede que outro processo assuma o mesmo arquivo simultaneamente.
     * A escolha da sessão que recebe o comando é coordenada pelo banco e pelo
     * registro de sessões, não pelo proprietário.
     *
     * @return proprietário com bloqueio adquirido, ou {@code null} quando a
     *         variável {@code TRACKING_DISPATCH_OWNER_FILE} está ausente
     * @throws IOException se o arquivo estiver indisponível, inválido ou em uso
     */
    private static DispatchOwner openDispatchOwner() throws IOException {
        String ownerPath = System.getenv("TRACKING_DISPATCH_OWNER_FILE");
        return ownerPath == null ? null : DispatchOwner.open(Path.of(ownerPath));
    }

    /**
     * Prepara o atendimento das conexões com a seleção de protocolo configurada
     * no ambiente.
     *
     * @param dataSource pool usado na consulta de dispositivos e na ingestão de
     *                   pacotes
     * @param sessions   registro em que o handler publica as sessões
     *                   identificadas
     * @param running    sinal de execução observado pelas tarefas TCP
     * @return handler compartilhado pelas conexões deste listener
     */
    private static TcpClientHandler createClientHandler(DataSource dataSource, SessionRegistry sessions,
            AtomicBoolean running) {
        // obtem o protocolo
        String protocolId = System.getenv().getOrDefault("TRACKING_PROTOCOL", "auto");
        // cria e repassa o handler
        var handler = new TcpClientHandler(new JdbcPacketIngestion(dataSource), new JdbcDeviceLookup(dataSource),
                sessions, protocolSelector(protocolId), running);
        log.info("TCP listener configured for {}", protocolId);
        return handler;
    }

    /**
     * Define como escolher o adaptador no início de cada conexão. Em modo
     * automático, a escolha depende do primeiro byte recebido. Um protocolo
     * fixo é resolvido na inicialização para que erros de configuração apareçam
     * antes da primeira conexão.
     *
     * @param protocolId identificador de um protocolo registrado ou
     *                   {@code auto}
     * @return função que recebe o primeiro byte da conexão como inteiro sem
     *         sinal
     * @throws IllegalArgumentException se o identificador fixo for desconhecido
     */
    private static IntFunction<ProtocolAdapter> protocolSelector(String protocolId) {
        var registry = new ProtocolRegistry(List.of(new SuntechAdapter(), new TeltonikaAdapter()));
        if ("auto".equals(protocolId)) {
            return registry::detect;
        }
        var fixedAdapter = registry.get(protocolId);
        return firstByte -> fixedAdapter;
    }

    /**
     * Agenda a primeira consulta à fila imediatamente e as seguintes um segundo
     * após a conclusão da rodada anterior.
     *
     * @param polling    executor de uma thread dedicado às rodadas do dispatcher
     * @param dataSource pool usado pelo dispatcher para consultar e atualizar a
     *                   fila
     * @param sessions   registro usado para localizar as sessões deste gateway
     * @param owner      proprietário que mantém o bloqueio do emissor durante as
     *                   rodadas
     * @param running    sinal consultado antes de iniciar uma rodada ou atender
     *                   outra sessão
     */
    private static void startCommandPolling(ScheduledExecutorService polling, DataSource dataSource,
            SessionRegistry sessions, DispatchOwner owner, AtomicBoolean running) {
        var dispatcher = new CommandDispatcher(dataSource, new DeviceCommandDispatchRepository(),
                new SessionCommandTransport(sessions), owner);
        polling.scheduleWithFixedDelay(() -> poll(dispatcher, sessions, running), 0, 1, TimeUnit.SECONDS);
    }

    /**
     * Aceita conexões e delega o atendimento ao executor. O fechamento do
     * listener pelo hook acorda a thread bloqueada em
     * {@link ServerSocket#accept()}.
     *
     * @param server        socket de escuta do gateway
     * @param executor      executor das tarefas TCP
     * @param handler       responsável pelo protocolo e pela sessão de cada conexão
     * @param running       sinal que distingue o encerramento solicitado de uma
     *                      falha
     *                      de socket
     * @param activeClients sockets acompanhados para fechamento durante o
     *                      encerramento
     * @throws IOException se ocorrer uma falha de E/S durante a aceitação ou o
     *                     fechamento
     */
    private static void acceptClients(ServerSocket server, ExecutorService executor, TcpClientHandler handler,
            AtomicBoolean running, Set<Socket> activeClients) throws IOException {
        log.info("Tracking TCP Gateway listening on {}:{}",
                server.getInetAddress().getHostAddress(), server.getLocalPort());
        while (running.get()) {
            try {
                Socket client = server.accept();
                // O encerramento pode ter começado enquanto accept() aguardava esta conexão.
                if (!running.get()) {
                    client.close();
                    break;
                }
                submitClient(executor, handler, client, activeClients);
            } catch (SocketException e) {
                if (!running.get()) {
                    break;
                }
                throw e;
            }
        }
    }

    /**
     * Entrega o socket ao handler em uma tarefa própria. Se a submissão falhar,
     * a thread que aceitou o socket assume o fechamento.
     *
     * @param executor      executor que recebe a tarefa da conexão
     * @param handler       responsável por atender e fechar o socket dentro da
     *                      tarefa
     * @param client        socket recém-aceito
     * @param activeClients conjunto compartilhado com a rotina de encerramento
     * @throws IOException se o fechamento do socket falhar após uma falha de
     *                     submissão
     */
    private static void submitClient(ExecutorService executor, TcpClientHandler handler, Socket client,
            Set<Socket> activeClients) throws IOException {
        // A tarefa pode terminar antes de submit() retornar. Registre o socket antes de
        // entregá-lo.
        activeClients.add(client);
        try {
            executor.submit(() -> {
                try {
                    handler.handle(client);
                } finally {
                    activeClients.remove(client);
                }
            });
        } catch (RuntimeException e) {
            try (client) {
                activeClients.remove(client);
            }
            throw e;
        }
    }

    /**
     * Fecha os sockets para desbloquear as leituras das tarefas TCP. Uma falha
     * individual é registrada e o fechamento prossegue para os demais clientes.
     *
     * @param activeClients sockets das tarefas que ainda podem estar em
     *                      execução
     */
    private static void closeActiveClients(Set<Socket> activeClients) {
        for (Socket client : activeClients) {
            try {
                client.close();
            } catch (IOException e) {
                log.debug("Error closing client socket", e);
            }
        }
    }

    /**
     * Solicita a parada do gateway e aguarda a liberação dos recursos pelo
     * {@code main}. Ao esgotar o prazo, tenta fechar os sockets ainda ativos.
     *
     * @param server          listener cujo fechamento interrompe a espera por
     *                        conexões
     * @param running         sinal compartilhado com o laço de aceitação, as
     *                        sessões e
     *                        o polling
     * @param gatewayFinished sinal liberado ao término do bloco de recursos do
     *                        gateway
     * @param activeClients   sockets que podem precisar de fechamento ao esgotar
     *                        o prazo
     */
    private static void shutdown(ServerSocket server, AtomicBoolean running,
            CountDownLatch gatewayFinished, Set<Socket> activeClients) {
        log.info("Shutdown requested for TCP Gateway");
        running.set(false);
        try {
            server.close();
        } catch (IOException e) {
            log.warn("Error closing TCP listener", e);
        }

        try {
            if (!gatewayFinished.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Gateway shutdown timeout exceeded");
                closeActiveClients(activeClients);
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
     * @param sessions   registro de sessões ativas do gateway
     * @param running    indicador compartilhado de execução do serviço
     */
    private static void poll(CommandDispatcher dispatcher,
            SessionRegistry sessions, AtomicBoolean running) {
        if (!running.get()) {
            return;
        }
        try {
            dispatcher.recover();
            for (DeviceSession session : sessions.onlineSessions()) {
                if (!running.get()) {
                    break;
                }
                dispatcher.dispatchNext(session.tenantId(), session.deviceId());
            }
        } catch (java.sql.SQLException error) {
            log.error("Command polling failed, SQLState={}", error.getSQLState());
        } catch (RuntimeException error) {
            log.error("Command polling failed", error);
        }
    }
}
