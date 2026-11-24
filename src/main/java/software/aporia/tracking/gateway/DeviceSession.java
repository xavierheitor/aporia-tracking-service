package software.aporia.tracking.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.LoggerFactory;

import software.aporia.tracking.command.dispatcher.CommandTransport.Endpoint;

/**
 * Vincula uma conexão TCP autorizada ao tenant, dispositivo, protocolo e
 * identidade
 * do rastreador. Cada conexão recebe um token próprio, usado na admissão no
 * banco
 * para distinguir reconexões do mesmo equipamento.
 *
 * <p>
 * O {@link TcpClientHandler} recebe os quadros e usa esta sessão para enviar
 * ACKs. O dispatcher também a utiliza para escrever comandos no mesmo socket.
 * </p>
 * <ol>
 * <li>{@link #send(byte[])} valida o conteúdo, adquire o bloqueio de escrita e
 * verifica a disponibilidade local da sessão. ACKs e comandos são
 * serializados.</li>
 * <li>Após adquirir o bloqueio, agenda um prazo de cinco segundos para a
 * escrita
 * e o flush. Se o prazo vencer ou ocorrer falha de E/S, invalida a sessão e
 * tenta
 * fechar o socket. O prazo não inclui a espera pelo bloqueio.</li>
 * <li>{@link #invalidate()} aguarda a escrita em curso e impede novos envios,
 * sem fechar o socket. O registro usa essa operação durante a substituição da
 * sessão.</li>
 * <li>{@link #close()} invalida a sessão e fecha o socket, interrompendo também
 * leituras pendentes do handler.</li>
 * </ol>
 *
 * <p>
 * A conclusão de {@code send()} indica somente escrita local concluída.
 * Não confirma recebimento ou execução pelo rastreador; uma falha pode ocorrer
 * após envio parcial. A persistência do resultado cabe ao dispatcher.
 * </p>
 */
public final class DeviceSession implements Endpoint {

    /** Prazo máximo de uma tentativa de escrita no socket, em segundos. */
    private static final int WRITE_TIMEOUT_SECONDS = 5;
    /**
     * Agendador compartilhado que interrompe escritas bloqueadas ao vencer o prazo.
     */
    private static final ScheduledThreadPoolExecutor deadlines;

    static {
        deadlines = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("tcp-write-deadline").factory());
        deadlines.setRemoveOnCancelPolicy(true);
    }

    /** Tenant proprietário do cadastro do equipamento. */
    private final UUID tenantId;
    /** Token exclusivo desta conexão, publicado no banco durante a admissão. */
    private final UUID sessionToken = UUID.randomUUID();

    /** Identificador interno do equipamento cadastrado. */
    private final UUID deviceId;
    /** Protocolo associado à sessão. */
    private final String protocol;
    /** Identidade que acompanha os quadros do equipamento. */
    private final String identity;

    /**
     * Conexão com o equipamento, fechada também para interromper E/S bloqueante.
     */
    private final Socket socket;

    /** Serializa escrita e invalidação da mesma sessão. */
    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Estado local visível também ao temporizador, que atua sem o bloqueio de
     * escrita.
     */
    private volatile boolean active = true;

    /**
     * Vincula o socket ao cadastro que o gateway identificou para esta conexão.
     *
     * @param tenantId identificador do locatário
     * @param deviceId identificador do dispositivo
     * @param protocol identificador do protocolo no cadastro
     * @param identity identidade declarada pelo equipamento no protocolo
     * @param socket   socket da conexão
     * @throws NullPointerException     se algum argumento for nulo
     * @throws IllegalArgumentException se o protocolo estiver em branco
     */
    public DeviceSession(
            UUID tenantId,
            UUID deviceId,
            String protocol,
            String identity,
            Socket socket) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.deviceId = Objects.requireNonNull(deviceId);
        this.protocol = Objects.requireNonNull(protocol);
        this.identity = Objects.requireNonNull(identity);

        this.socket = Objects.requireNonNull(socket);

        if (protocol.isBlank()) {
            throw new IllegalArgumentException("INVALID_PROTOCOL");
        }
    }

    /**
     * Identifica esta conexão, inclusive quando o mesmo dispositivo reconecta.
     *
     * @return token estável durante a vida da sessão
     */
    @Override
    public UUID sessionToken() {
        return sessionToken;
    }

    /**
     * Retorna o identificador do locatário.
     *
     * @return o identificador do locatário
     */
    public UUID tenantId() {
        return tenantId;
    }

    /**
     * Retorna o identificador do dispositivo.
     *
     * @return o identificador do dispositivo
     */
    public UUID deviceId() {
        return deviceId;
    }

    /**
     * Retorna o identificador do protocolo.
     *
     * @return o identificador do protocolo
     */
    @Override
    public String protocol() {
        return protocol;
    }

    /**
     * Retorna a identidade declarada pelo equipamento no protocolo.
     *
     * @return a identidade declarada pelo equipamento no protocolo
     */
    @Override
    public String identity() {
        return identity;
    }

    /**
     * Consulta o estado local do socket e da sessão. O equipamento remoto pode
     * desconectar antes da próxima escrita.
     *
     * @return true se a sessão está conectada, false caso contrário
     */
    public boolean isConnected() {
        return active
                && socket.isConnected()
                && !socket.isClosed()
                && !socket.isOutputShutdown();
    }

    /**
     * Serializa as escritas no socket. O vencimento do prazo encerra a conexão
     * e torna o resultado da tentativa incerto.
     *
     * @param payload bytes originais do quadro ou comando
     * @throws NullPointerException     se o payload for nulo
     * @throws IllegalArgumentException se o payload estiver vazio
     * @throws IOException              se a sessão estiver indisponível, a escrita
     *                                  falhar ou seu prazo vencer
     */
    @Override
    public void send(byte[] payload) throws IOException {

        Objects.requireNonNull(payload);

        if (payload.length == 0) {
            throw new IllegalArgumentException(
                    "EMPTY_COMMAND_PAYLOAD");
        }

        writeLock.lock();

        try {
            if (!isConnected()) {
                throw new IOException("DEVICE_DISCONNECTED");
            }

            var writing = new AtomicBoolean(true);
            // O CAS decide quem terminou primeiro: a escrita ou o temporizador.
            // O temporizador fecha o socket sem adquirir writeLock para desbloquear a
            // escrita.
            var deadline = deadlines.schedule(() -> {
                if (writing.compareAndSet(true, false)) {
                    abortSocket();
                }
            }, WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            try {
                OutputStream output = socket.getOutputStream();
                output.write(payload);
                output.flush();
                if (!writing.compareAndSet(true, false)) {
                    throw new IOException("TCP_WRITE_TIMEOUT");
                }
            } catch (IOException error) {
                // A falha pode ter ocorrido após envio parcial; esta sessão não será
                // reutilizada.
                abortSocket();
                throw error;
            } finally {
                // Cancela a tarefa pendente antes de liberar o bloqueio no finally externo.
                writing.set(false);
                deadline.cancel(false);
            }

        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Invalida e tenta fechar o socket sem adquirir o bloqueio de escrita.
     * Usado pelo temporizador para interromper E/S e pelo fechamento normal
     * depois da invalidação sincronizada. Falhas de fechamento são registradas.
     */
    private void abortSocket() {
        active = false;
        try {
            socket.close();
        } catch (IOException error) {
            LoggerFactory.getLogger(DeviceSession.class).warn("Failed to close command socket", error);
        }
    }

    /**
     * Rejeita a troca de identidade dentro da mesma conexão.
     *
     * @param receivedIdentity identidade declarada pelo equipamento no
     *                         protocolo
     * @throws IllegalArgumentException se a identidade declarada pelo
     *                                  equipamento no protocolo for diferente da
     *                                  identidade atual
     */
    public void validateIdentity(String receivedIdentity) {
        if (!identity.equals(receivedIdentity)) {
            throw new IllegalArgumentException(
                    "SESSION_IDENTITY_MISMATCH");
        }
    }

    /**
     * Impede novas escritas após aguardar a conclusão de uma escrita que já
     * detenha o bloqueio.
     */
    public void invalidate() {
        writeLock.lock();

        try {
            active = false;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Invalida a sessão e fecha o socket para interromper as leituras.
     *
     * @throws IOException prevista no contrato de fechamento. Esta implementação
     *                     captura e registra falhas do socket
     */
    public void close() throws IOException {
        invalidate();
        abortSocket();
    }

}
