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
 * Controla o ciclo de vida do listener TCP, das conexões e do envio opcional de comandos. O {@link TcpClientHandler}
 * identifica os equipamentos e publica suas sessões no {@link SessionRegistry}, também consultado pelo dispatcher.
 *
 * <ol>
 * <li>O construtor recebe o pool e a configuração. {@link #start()} cria o registro de sessões, o handler, o listener
 * e, quando configurado, abre o arquivo persistente do emissor com bloqueio exclusivo.</li>
 * <li>{@code runGateway()} executa em uma thread própria e cria os executores de atendimento TCP e polling. Se houver
 * proprietário, {@code startCommandPolling()} agenda {@code poll()}: a primeira rodada é imediata e as seguintes
 * começam um segundo após a conclusão da rodada anterior.</li>
 * <li>{@code acceptClients()} recebe sockets e os entrega a {@code submitClient()}. Cada conexão é atendida em uma
 * thread virtual e acompanhada para fechamento.</li>
 * <li>{@link #requestStop()} sinaliza a parada e fecha o listener e os clientes. Ao sair da aceitação,
 * {@code runGateway()} fecha os recursos em ordem inversa: polling, proprietário, executor TCP e listener. O bloqueio
 * do emissor permanece até as rodadas terminarem.</li>
 * <li>{@link #awaitTermination()} aguarda a thread e a liberação desses recursos, sem timeout. Falhas da thread ficam
 * disponíveis em {@link #getFailure()}.</li>
 * </ol>
 *
 * <p>
 * O pool JDBC pertence ao chamador e deve permanecer aberto até a terminação do gateway. O {@link #main(String[])}
 * oferece a execução independente: lê as variáveis de ambiente, cria o pool e registra o hook de encerramento. Quem usa
 * a instância diretamente controla a configuração, a parada e o fechamento do pool.
 * </p>
 */
public class TcpGateway {

    private static final Logger log = LoggerFactory.getLogger(TcpGateway.class);

    // O pool é externo: o gateway encerra suas tarefas antes de o chamador fechá-lo.
    private final DataSource dataSource;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<Socket> activeClients = ConcurrentHashMap.newKeySet();

    private final String bindAddress;
    private final int port;
    private final String protocolId;
    private final String dispatchOwnerFile;

    // volatile publica as mudanças da thread de atendimento para quem consulta ou solicita a parada.
    private volatile ServerSocket server;
    private volatile Thread gatewayThread;
    private volatile Throwable failure;

    /**
     * Guarda os recursos e a configuração; não abre sockets nem inicia threads.
     *
     * @param dataSource        pool externo, que não será fechado pelo gateway
     * @param bindAddress       endereço local em que o listener deve escutar
     * @param port              porta TCP; zero permite ao sistema operacional escolher uma porta livre
     * @param protocolId        protocolo registrado ou {@code auto} para detectar pelo primeiro byte
     * @param dispatchOwnerFile caminho do arquivo persistente do emissor; nulo ou em branco desabilita o polling de
     *                          comandos
     * @throws NullPointerException se pool, endereço ou protocolo forem nulos
     */
    public TcpGateway(
            DataSource dataSource,
            String bindAddress,
            int port,
            String protocolId,
            String dispatchOwnerFile) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource);
        this.bindAddress = java.util.Objects.requireNonNull(bindAddress);
        this.port = port;
        this.protocolId = java.util.Objects.requireNonNull(protocolId);
        this.dispatchOwnerFile = dispatchOwnerFile;
    }

    /**
     * Prepara o atendimento e inicia a thread do gateway. Se ela ainda estiver viva, retorna sem iniciar outra. Uma
     * nova partida após a terminação cria novas sessões e limpa a falha anterior.
     *
     * <p>
     * Listener e proprietário são abertos antes de iniciar a thread. Se essa preparação falhar, tenta fechar os
     * recursos já adquiridos e propaga a falha. Falhas posteriores da thread são consultadas por {@link #getFailure()}.
     * </p>
     *
     * @throws IOException              se falhar a abertura do listener ou do arquivo do emissor
     * @throws IllegalArgumentException se porta, protocolo ou configuração forem inválidos
     */
    public synchronized void start() throws IOException {
        // start e requestStop usam o mesmo monitor para serializar as mudanças do ciclo de vida.
        if (gatewayThread != null && gatewayThread.isAlive()) {
            return;
        }
        failure = null;

        // Cada partida tem seu registro; sessões de uma execução anterior não são reaproveitadas.
        var sessions = new SessionRegistry();
        var handler = new TcpClientHandler(
                new JdbcPacketIngestion(dataSource),
                new JdbcDeviceLookup(dataSource),
                sessions,
                protocolSelector(protocolId),
                running);

        ServerSocket listener = new ServerSocket(port, 50, InetAddress.getByName(bindAddress));
        DispatchOwner owner = null;
        try {
            if (dispatchOwnerFile != null && !dispatchOwnerFile.isBlank()) {
                owner = DispatchOwner.open(Path.of(dispatchOwnerFile));
            }
            // A lambda captura uma referência fixa; a thread assume o fechamento desses recursos.
            final DispatchOwner dispatchOwner = owner;
            // Publica o listener e habilita o atendimento antes de iniciar a thread.
            server = listener;
            running.set(true);
            gatewayThread = Thread.ofPlatform()
                    .name("tracking-tcp-gateway")
                    .unstarted(() -> runGateway(listener, sessions, handler, dispatchOwner));
            gatewayThread.start();
        } catch (IOException | RuntimeException | Error error) {
            // A partida não foi concluída: os recursos ainda precisam ser fechados por esta thread.
            running.set(false);
            try {
                listener.close();
            } catch (IOException closeError) {
                // Preserva a falha original e acrescenta a falha de limpeza ao diagnóstico.
                error.addSuppressed(closeError);
            }
            if (owner != null) {
                try {
                    owner.close();
                } catch (IOException closeError) {
                    error.addSuppressed(closeError);
                }
            }
            throw error;
        }
    }

    /**
     * Executa a aceitação e o polling com os recursos pertencentes a esta partida. Ao sair, sinaliza a parada, fecha os
     * clientes e aguarda o fechamento dos executores. Falhas são registradas e guardadas; erros fatais também são
     * relançados na thread.
     *
     * @param listener socket de escuta já aberto por {@link #start()}
     * @param sessions registro compartilhado entre recepção e dispatcher
     * @param handler  responsável por atender as conexões desta partida
     * @param owner    proprietário com bloqueio adquirido, ou nulo para não enviar comandos
     */
    private void runGateway(
            ServerSocket listener,
            SessionRegistry sessions,
            TcpClientHandler handler,
            DispatchOwner owner) {

        // O fechamento ocorre em ordem inversa: polling, owner, tarefas TCP e listener. Isso mantém o bloqueio do
        // emissor enquanto ainda houver uma rodada de envio em execução.
        try (
                listener;
                var executor = Executors.newVirtualThreadPerTaskExecutor();
                owner;
                var polling = Executors.newSingleThreadScheduledExecutor()
        ) {
            try {
                if (owner != null) {
                    startCommandPolling(polling, dataSource, sessions, owner, running);
                }
                acceptClients(listener, executor, handler, running, activeClients);
            } finally {
                running.set(false);
                log.info("TCP Gateway stopping");
                closeActiveClients(activeClients);
            }
        } catch (IOException | RuntimeException | Error error) {
            // A falha ocorreu fora da thread chamadora; main a consulta depois do join.
            failure = error;
            log.error("TCP Gateway failed", error);
            if (error instanceof Error fatal) {
                throw fatal;
            }
        } finally {
            running.set(false);
            log.info("TCP Gateway stopped");
        }
    }

    /**
     * Solicita a parada sem aguardar a terminação da thread. Fecha o listener para desbloquear
     * {@link ServerSocket#accept()} e os clientes para interromper suas leituras. Falhas de fechamento são registradas
     * e não impedem as demais tentativas. Pode ser chamado antes da partida ou repetidamente durante o encerramento.
     */
    public synchronized void requestStop() {
        // Primeiro impede novos ciclos; fechar o listener desbloqueia o accept em andamento.
        running.set(false);
        ServerSocket listener = server;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException error) {
                log.warn("Error closing TCP listener", error);
            }
        }
        closeActiveClients(activeClients);
    }

    /**
     * Aguarda, sem timeout, o fim da thread observada e do fechamento de seus recursos. Retorna imediatamente se não
     * houver thread ou se ela for a própria chamadora. Não solicita a parada e não propaga a falha registrada pela
     * thread.
     *
     * @throws InterruptedException se a thread chamadora for interrompida durante a espera
     */
    public void awaitTermination() throws InterruptedException {
        Thread thread = gatewayThread;
        if (thread != null && thread != Thread.currentThread()) {
            // Não faz join na própria thread, pois ela ficaria esperando por si mesma.
            thread.join();
        }
    }

    /**
     * Consulta se a thread do gateway ainda está viva, inclusive durante o encerramento. Para verificar a
     * disponibilidade local do listener, use {@link #isAcceptingConnections()}.
     *
     * @return verdadeiro enquanto a thread observada ainda estiver viva
     */
    public boolean isRunning() {
        Thread thread = gatewayThread;
        // A thread pode continuar viva enquanto fecha recursos, mesmo após running virar false.
        return thread != null && thread.isAlive();
    }

    /**
     * Consulta o sinal de execução, a thread e o estado local do listener. O resultado é uma fotografia do momento e
     * pode mudar logo após a consulta.
     *
     * @return verdadeiro se a execução estiver habilitada, a thread viva e o listener aberto
     */
    public boolean isAcceptingConnections() {
        ServerSocket listener = server;
        return running.get()
                && isRunning()
                && listener != null
                && !listener.isClosed();
    }

    /**
     * Obtém a falha capturada por {@code runGateway()}. Falhas síncronas de {@link #start()} são propagadas diretamente
     * e não são armazenadas aqui.
     *
     * @return falha da thread, ou nulo se nenhuma foi registrada desde a última partida
     */
    public Throwable getFailure() {
        return failure;
    }

    /**
     * Obtém a porta atribuída ao listener, útil quando a porta configurada é zero. A consulta não indica que o gateway
     * ainda aceita conexões.
     *
     * @return porta do último listener publicado, ou -1 se nenhum foi publicado
     */
    public int getLocalPort() {
        ServerSocket listener = server;
        return listener == null ? -1 : listener.getLocalPort();
    }

    /**
     * Executa o gateway independente com configuração de ambiente. Cria o pool, chama {@link #start()}, instala o hook
     * de parada e aguarda a terminação. Ao terminar, solicita a parada, aguarda novamente e tenta remover o hook antes
     * de fechar o pool.
     *
     * <p>
     * {@code TRACKING_TCP_BIND} e {@code TRACKING_TCP_PORT} configuram o listener; {@code
     * TRACKING_PROTOCOL} seleciona o protocolo. Um caminho não vazio em {@code
     * TRACKING_DISPATCH_OWNER_FILE} habilita o emissor de comandos.
     * </p>
     *
     * @param args argumentos da linha de comando, atualmente ignorados
     * @throws IOException           se a partida falhar ao abrir o listener ou o arquivo do emissor
     * @throws InterruptedException  se a espera pela terminação for interrompida
     * @throws IllegalStateException se a thread do gateway terminar com uma falha registrada
     */
    public static void main(String[] args)
            throws IOException, InterruptedException {
        try (var dataSource = Database.createDataSource()) {
            var gateway = new TcpGateway(
                    dataSource,
                    System.getenv().getOrDefault(
                            "TRACKING_TCP_BIND",
                            "0.0.0.0"),
                    Integer.parseInt(
                            System.getenv().getOrDefault(
                                    "TRACKING_TCP_PORT",
                                    "5500")),
                    System.getenv().getOrDefault(
                            "TRACKING_PROTOCOL",
                            "auto"),
                    System.getenv("TRACKING_DISPATCH_OWNER_FILE"));
            gateway.start();

            // O hook aguarda a liberação dos recursos TCP antes de terminar o encerramento da JVM.
            Thread shutdownHook = new Thread(() -> {
                gateway.requestStop();
                try {
                    gateway.awaitTermination();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }, "tracking-gateway-shutdown");

            try {
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                gateway.awaitTermination();
                // O join termina também em caso de falha; confira o resultado antes de retornar.
                if (gateway.getFailure() != null) {
                    throw new IllegalStateException(
                            "TCP Gateway terminated unexpectedly",
                            gateway.getFailure());
                }
            } finally {
                gateway.requestStop();
                gateway.awaitTermination();
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // Durante o encerramento da JVM, a remoção do hook deixa de ser permitida.
                }
            }
        }
    }

    /**
     * Define como escolher o adaptador no início de cada conexão. Em modo automático, a escolha depende do primeiro
     * byte recebido. Um protocolo fixo é resolvido na inicialização para que erros de configuração apareçam antes da
     * primeira conexão.
     *
     * @param protocolId identificador de um protocolo registrado ou {@code auto}
     * @return função que recebe o primeiro byte da conexão como inteiro sem sinal
     * @throws IllegalArgumentException se o identificador fixo for desconhecido
     */
    private static IntFunction<ProtocolAdapter> protocolSelector(String protocolId) {
        var registry = new ProtocolRegistry(List.of(new SuntechAdapter(), new TeltonikaAdapter()));
        if ("auto".equals(protocolId)) {
            return registry::detect;
        }
        // Resolve o protocolo fixo na partida para falhar antes de aceitar conexões.
        var fixedAdapter = registry.get(protocolId);
        return firstByte -> fixedAdapter;
    }

    /**
     * Agenda a primeira consulta à fila imediatamente e as seguintes um segundo após a conclusão da rodada anterior.
     *
     * @param polling    executor de uma thread dedicado às rodadas do dispatcher
     * @param dataSource pool usado pelo dispatcher para consultar e atualizar a fila
     * @param sessions   registro usado para localizar as sessões deste gateway
     * @param owner      proprietário que mantém o bloqueio do emissor durante as rodadas
     * @param running    sinal consultado antes de iniciar uma rodada ou atender outra sessão
     */
    private static void startCommandPolling(
            ScheduledExecutorService polling,
            DataSource dataSource,
            SessionRegistry sessions,
            DispatchOwner owner,
            AtomicBoolean running) {
        var dispatcher = new CommandDispatcher(
                dataSource,
                new DeviceCommandDispatchRepository(),
                new SessionCommandTransport(sessions),
                owner);
        // O intervalo começa após o fim da rodada: rodadas lentas não acumulam novas execuções.
        polling.scheduleWithFixedDelay(() -> poll(dispatcher, sessions, running), 0, 1, TimeUnit.SECONDS);
    }

    /**
     * Aceita conexões e delega o atendimento ao executor. O fechamento do listener por {@link #requestStop()} acorda a
     * thread bloqueada em {@link ServerSocket#accept()}.
     *
     * @param server        socket de escuta do gateway
     * @param executor      executor das tarefas TCP
     * @param handler       responsável pelo protocolo e pela sessão de cada conexão
     * @param running       sinal que distingue o encerramento solicitado de uma falha de socket
     * @param activeClients sockets acompanhados para fechamento durante o encerramento
     * @throws IOException se ocorrer uma falha de E/S durante a aceitação ou o fechamento
     */
    private static void acceptClients(
            ServerSocket server,
            ExecutorService executor,
            TcpClientHandler handler,
            AtomicBoolean running,
            Set<Socket> activeClients) throws IOException {
        log.info(
                "Tracking TCP Gateway listening on {}:{}",
                server.getInetAddress().getHostAddress(),
                server.getLocalPort());
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
                // Fechar o listener na parada provoca SocketException; nesse caso a saída é esperada.
                if (!running.get()) {
                    break;
                }
                throw e;
            }
        }
    }

    /**
     * Entrega o socket ao handler em uma tarefa própria. Se a submissão falhar, a thread que aceitou o socket assume o
     * fechamento.
     *
     * @param executor      executor que recebe a tarefa da conexão
     * @param handler       responsável por atender e fechar o socket dentro da tarefa
     * @param client        socket recém-aceito
     * @param activeClients conjunto compartilhado com a rotina de encerramento
     * @throws IOException se o fechamento do socket falhar após uma falha de submissão
     */
    private static void submitClient(
            ExecutorService executor,
            TcpClientHandler handler,
            Socket client,
            Set<Socket> activeClients) throws IOException {
        // A tarefa pode terminar antes de submit() retornar. Registre o socket antes de entregá-lo.
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
            // Sem tarefa submetida, ninguém assumiu o socket: o chamador faz a limpeza.
            try (client) {
                activeClients.remove(client);
            }
            throw e;
        }
    }

    /**
     * Fecha os sockets para desbloquear as leituras das tarefas TCP. Uma falha individual é registrada e o fechamento
     * prossegue para os demais clientes.
     *
     * @param activeClients sockets das tarefas que ainda podem estar em execução
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
     * Executa a recuperação e consulta a fila de comandos para os dispositivos com sessão registrada.
     *
     * @param dispatcher coordenador do envio de comandos
     * @param sessions   registro de sessões ativas do gateway
     * @param running    indicador compartilhado de execução do serviço
     */
    private static void poll(
            CommandDispatcher dispatcher,
            SessionRegistry sessions,
            AtomicBoolean running) {
        if (!running.get()) {
            return;
        }
        try {
            // Reconcilia tentativas interrompidas antes de procurar novos comandos elegíveis.
            dispatcher.recover();
            for (DeviceSession session : sessions.onlineSessions()) {
                if (!running.get()) {
                    break;
                }
                dispatcher.dispatchNext(session.tenantId(), session.deviceId());
            }
        } catch (java.sql.SQLException error) {
            // A falha fica visível, mas não cancela as próximas rodadas do agendador.
            log.error("Command polling failed, SQLState={}", error.getSQLState());
        } catch (RuntimeException error) {
            log.error("Command polling failed", error);
        }
    }
}
