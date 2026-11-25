package software.aporia.tracking.worker;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Mantém o laço do {@link TrackingWorker} integrado ao ciclo de vida do Spring. Uma thread processa um pacote por
 * chamada, sem pausa enquanto houver trabalho. A fila sem pacote elegível provoca espera de um segundo; falhas provocam
 * espera de três segundos.
 *
 * <p>
 * A parada libera a espera pelo latch, sem interromper à força a transação em curso. A conclusão é observada pelo
 * término da thread, e não apenas pelo sinal {@code running}.
 * </p>
 */
public final class WorkerLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(WorkerLifecycle.class);

    /** Intervalo entre consultas quando não há pacote elegível. */
    private static final long IDLE_DELAY_MS = 1_000;
    /** Intervalo para evitar repetir imediatamente uma operação que falhou. */
    private static final long FAILURE_DELAY_MS = 3_000;

    private final TrackingWorker worker;

    private final AtomicBoolean running = new AtomicBoolean(false);

    // volatile permite que o coordenador observe a thread publicada na partida.
    private volatile Thread processingThread;

    // O latch libera as pausas antecipadamente quando a parada é solicitada.
    private volatile CountDownLatch stopSignal = new CountDownLatch(0);

    private volatile long lastHeartbeatNanos = System.nanoTime();

    /**
     * Guarda o orquestrador usado em cada consulta à fila.
     *
     * @param worker consumidor configurado com persistência e decodificadores
     * @throws NullPointerException se o worker for nulo
     */
    public WorkerLifecycle(TrackingWorker worker) {
        this.worker = Objects.requireNonNull(worker);
    }

    /**
     * Inicia a thread, caso não exista outra ainda viva, e prepara um novo sinal de parada. Uma nova partida após a
     * terminação não reaproveita o latch já liberado.
     */
    @Override
    public synchronized void start() {
        if (processingThread != null
                && processingThread.isAlive()) {
            return;
        }

        // CountDownLatch não pode ser rearmado: cada partida precisa de uma nova instância.
        stopSignal = new CountDownLatch(1);
        running.set(true);

        processingThread = Thread.ofPlatform()
                .name("tracking-worker")
                .unstarted(this::runProcessingLoop);

        lastHeartbeatNanos = System.nanoTime();

        processingThread.start();

        log.info("Tracking worker started");
    }

    /**
     * Executa consultas sucessivas até observar a parada. Registra falhas de um ciclo e mantém o serviço ativo para a
     * próxima tentativa, respeitando o intervalo de recuperação. O estado de execução é desabilitado mesmo se a thread
     * terminar com falha inesperada.
     */
    private void runProcessingLoop() {

        try {
            while (running.get()) {

                lastHeartbeatNanos = System.nanoTime();

                try {
                    if (!worker.processOne()) {
                        awaitNextCycle(IDLE_DELAY_MS);
                    }

                } catch (RuntimeException error) {
                    log.error("Worker processing failed", error);

                    awaitNextCycle(FAILURE_DELAY_MS);
                }
            }

        } finally {
            running.set(false);

            log.info("Tracking worker stopped");
        }
    }

    /**
     * Aguarda o intervalo ou a liberação do sinal de parada, o que ocorrer primeiro. Uma interrupção sinaliza a saída
     * do laço e é preservada na thread atual.
     *
     * @param delayMillis intervalo máximo de espera em milissegundos
     */
    private void awaitNextCycle(long delayMillis) {
        try {
            // Ao contrário de sleep, a espera pelo latch termina assim que requestStop o libera.
            stopSignal.await(delayMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            running.set(false);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Impede novos ciclos e libera uma eventual pausa. Usa o mesmo monitor da partida para capturar a thread
     * correspondente ao pedido de parada.
     *
     * @return thread que deve ser aguardada, ou nulo se nunca houve partida
     */
    private synchronized Thread requestStop() {
        running.set(false);
        // Libera tanto a pausa de fila vazia quanto a pausa de recuperação após falha.
        stopSignal.countDown();
        return processingThread;
    }

    /**
     * Solicita a parada e aguarda a thread, sem timeout próprio nem interrupção forçada do pacote. Retorna sem espera
     * se não houver thread ou se a chamadora for a própria thread de processamento.
     *
     * @throws IllegalStateException se a thread chamadora for interrompida durante a espera
     */
    @Override
    public void stop() {
        Thread thread = requestStop();

        if (thread == null || thread == Thread.currentThread()) {
            return;
        }

        try {
            thread.join();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while stopping tracking worker",
                    error);
        }
    }

    /**
     * Solicita a parada e confirma a terminação ao Spring. Se a thread ainda estiver viva, uma thread auxiliar a
     * aguarda antes de executar o callback. Uma espera interrompida é registrada sem confirmar uma terminação não
     * observada.
     *
     * @param callback confirmação executada após observar o término da thread
     */
    @Override
    public void stop(@NonNull Runnable callback) {
        Thread thread = requestStop();

        if (thread == null || !thread.isAlive()) {
            callback.run();
            return;
        }

        Thread.ofPlatform()
                .name("tracking-worker-shutdown")
                .daemon(true)
                .start(() -> {
                    try {
                        thread.join();
                        // Só confirma a parada depois de concluir o ciclo em andamento e sair do laço.
                        callback.run();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        log.error("Worker shutdown interrupted", error);
                    }
                });
    }

    /**
     * Consulta a existência da thread, incluindo o intervalo entre o pedido de parada e seu término.
     *
     * @return verdadeiro enquanto a thread de processamento estiver viva
     */
    @Override
    public boolean isRunning() {
        Thread thread = processingThread;
        return thread != null && thread.isAlive();
    }

    /**
     * Habilita a partida automática se o perfil tiver criado este bean.
     *
     * @return sempre verdadeiro
     */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * Define a ordem de partida e parada entre componentes de ciclo de vida. Fases menores iniciam antes e encerram
     * depois; o mesmo valor do gateway não estabelece uma ordem relativa entre os dois componentes.
     *
     * @return fase 100
     */
    @Override
    public int getPhase() {
        return 100;
    }

    public double getHeartbeatAgeSeconds() {

        return (System.nanoTime() - lastHeartbeatNanos)
                / 1_000_000_000.0;
    }
}
