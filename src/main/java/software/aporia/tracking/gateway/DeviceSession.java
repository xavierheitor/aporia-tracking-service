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
 * Representa a conexão de um dispositivo habilitado. A escrita e a invalidação
 * compartilham o mesmo bloqueio.
 */
public final class DeviceSession implements Endpoint {

    /** Prazo máximo de uma tentativa de escrita no socket, em segundos. */
    private static final int WRITE_TIMEOUT_SECONDS = 5;
    /** Agendador compartilhado que interrompe escritas bloqueadas ao vencer o prazo. */
    private static final ScheduledThreadPoolExecutor deadlines;

    static {
        deadlines = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("tcp-write-deadline").factory());
        deadlines.setRemoveOnCancelPolicy(true);
    }

    /** Tenant proprietário do cadastro do equipamento. */
    private final UUID tenantId;
    /** Identificador interno do equipamento cadastrado. */
    private final UUID deviceId;
    /** Protocolo associado à sessão. */
    private final String protocol;
    /** Identidade que acompanha os quadros do equipamento. */
    private final String identity;

    /** Conexão com o equipamento, fechada também para interromper E/S bloqueante. */
    private final Socket socket;

    /** Serializa escrita e invalidação da mesma sessão. */
    private final ReentrantLock writeLock = new ReentrantLock();

    /** Indica se a sessão ainda aceita escrita após desconexão ou substituição. */
    private volatile boolean active = true;

    /**
     * Vincula o socket ao cadastro que o gateway identificou para esta conexão.
     *
     * @param tenantId identificador do locatário
     * @param deviceId identificador do dispositivo
     * @param protocol identificador do protocolo no cadastro
     * @param identity identidade declarada pelo equipamento no protocolo
     * @param socket socket da conexão
     * @throws NullPointerException se algum argumento for nulo
     * @throws IllegalArgumentException se o protocolo estiver em branco
     */
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
     * @throws NullPointerException se o payload for nulo
     * @throws IllegalArgumentException se o payload estiver vazio
     * @throws IOException se a sessão estiver indisponível, a escrita falhar ou seu prazo vencer
     */
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
            // Verifica se a sessão está conectada
            if (!isConnected()) {
                throw new IOException("DEVICE_DISCONNECTED");
            }

            var writing = new AtomicBoolean(true);
            // O temporizador fecha o socket para desbloquear uma escrita que excedeu o prazo.
            // O sinal atômico decide se a escrita terminou antes do prazo ou se o temporizador venceu.
            var deadline = deadlines.schedule(() -> {
                if (writing.compareAndSet(true, false)) {
                    abortSocket();
                }
            }, WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try {
                // Escreve o payload no socket.
                OutputStream output = socket.getOutputStream();
                output.write(payload);
                output.flush();
                if (!writing.compareAndSet(true, false)) {
                    throw new IOException("TCP_WRITE_TIMEOUT");
                }
            } catch (IOException error) {
                // Fecha o socket se a escrita falhar.
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
     * Fecha o socket e invalida a sessão.
     */
    private void abortSocket() {
        // Invalida a sessão.
        active = false;
        try {
            socket.close();
        } catch (IOException error) {
            LoggerFactory.getLogger(DeviceSession.class).warn("Failed to close command socket", error);
        }
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
     * Rejeita a troca de identidade dentro da mesma conexão.
     *
     * @param receivedIdentity identidade declarada pelo equipamento no
     * protocolo
     * @throws IllegalArgumentException se a identidade declarada pelo
     * equipamento no protocolo for diferente da identidade atual
     */
    public void validateIdentity(String receivedIdentity) {
        if (!identity.equals(receivedIdentity)) {
            throw new IllegalArgumentException(
                    "SESSION_IDENTITY_MISMATCH"
            );
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
     * @throws IOException prevista no contrato de fechamento. Esta implementação captura e registra falhas do socket
     */
    public void close() throws IOException {
        invalidate();
        try {
            socket.close();
        } catch (IOException error) {
            LoggerFactory.getLogger(DeviceSession.class).warn("Failed to close command socket", error);
        }
    }

}
