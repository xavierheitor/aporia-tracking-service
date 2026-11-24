package software.aporia.tracking.worker;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.aporia.tracking.storage.Database;

/**
 * Ponto de entrada do processo que consome a fila de pacotes brutos.
 *
 * <ol>
 * <li>{@link #main(String[])} registra o hook de encerramento, abre o pool JDBC
 * e cria o {@link TrackingWorker}.</li>
 * <li>{@code runProcessingLoop()} solicita um pacote por ciclo. Havendo
 * trabalho,
 * inicia o próximo ciclo imediatamente; sem pacote elegível, espera um
 * segundo.</li>
 * <li>Falhas de processamento são registradas e provocam uma pausa de três
 * segundos
 * antes da próxima consulta. A transação do pacote é controlada pela
 * persistência.</li>
 * <li>No encerramento da JVM, {@code shutdown()} sinaliza a parada e aguarda
 * até dez segundos pela saída do laço e pelo fechamento do pool.</li>
 * </ol>
 *
 * <p>
 * O sinal de parada não interrompe o pacote em processamento. O laço deixa de
 * iniciar novos ciclos quando observa esse sinal. Se o prazo do hook se
 * esgotar,
 * a JVM pode encerrar sem aguardar a conclusão da operação em curso.
 * </p>
 */
public class WorkerApplication {
    private static final Logger log = LoggerFactory.getLogger(WorkerApplication.class);

    /**
     * Pausa quando não há pacote elegível, evitando consultas contínuas à fila
     * vazia.
     */
    private static final long IDLE_DELAY_MS = 1_000;
    /** Pausa após falha, evitando repetir imediatamente uma operação com erro. */
    private static final long FAILURE_DELAY_MS = 3_000;
    /** Prazo do hook para aguardar o laço e o fechamento dos recursos. */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    /**
     * Prepara os recursos e mantém o consumidor ativo até a parada ou interrupção.
     * O pool é fechado antes de liberar o hook que aguarda o encerramento.
     *
     * @param args argumentos da linha de comando, atualmente ignorados
     * @throws RuntimeException se a inicialização ou o fechamento dos recursos
     *                          falhar
     */
    public static void main(String[] args) {
        var running = new AtomicBoolean(true);
        var workerFinished = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> shutdown(running, workerFinished), "tracking-worker-shutdown"));

        try (var dataSource = Database.createDataSource()) {
            var worker = new TrackingWorker(dataSource);
            log.info("Aporia Tracking Worker started");
            runProcessingLoop(worker, running);
        } finally {
            // O try-with-resources já tentou fechar o pool, inclusive em caso de falha.
            // Liberar antes disso permitiria que o hook terminasse com recursos ainda
            // ativos.
            workerFinished.countDown();
            log.info("Aporia Tracking Worker stopped");
        }
    }

    /**
     * Consulta a fila enquanto a execução estiver habilitada. Uma falha de um ciclo
     * não encerra o serviço; uma interrupção durante a pausa encerra o laço.
     *
     * @param worker  orquestrador que trata no máximo um pacote por chamada
     * @param running sinal compartilhado com o hook de encerramento
     */
    private static void runProcessingLoop(TrackingWorker worker, AtomicBoolean running) {
        while (running.get()) {
            try {
                // true inclui rejeição e retentativa persistidas: também são trabalho
                // realizado.
                // Só uma consulta sem pacote elegível precisa da pausa de fila vazia.
                if (!worker.processOne() && !pause(IDLE_DELAY_MS)) {
                    break;
                }
            } catch (RuntimeException error) {
                log.error("Worker processing failed", error);
                // Após a parada, não inicia outra espera para recuperação de falha.
                if (!running.get() || !pause(FAILURE_DELAY_MS)) {
                    break;
                }
            }
        }
    }

    /**
     * Suspende a thread entre consultas e preserva uma eventual interrupção.
     * Não altera o agendamento de retentativas de pacotes, definido pelo worker.
     *
     * @param delayMillis intervalo de espera em milissegundos
     * @return falso se a thread for interrompida e o chamador precisar sair do laço
     */
    private static boolean pause(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
            return true;
        } catch (InterruptedException error) {
            // sleep limpa o sinal ao lançar a exceção; restaure-o para os chamadores.
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Solicita a parada e aguarda a liberação dos recursos pelo main.
     * O vencimento do prazo é registrado, sem forçar o fechamento do pool por outra
     * thread.
     *
     * @param running        sinal que impede novos ciclos quando observado pelo
     *                       laço
     * @param workerFinished sinal liberado após a tentativa de fechamento do pool
     */
    private static void shutdown(AtomicBoolean running, CountDownLatch workerFinished) {
        log.info("Shutdown requested for Tracking Worker");
        running.set(false);
        try {
            // Mantém a JVM viva enquanto o main conclui o ciclo e fecha os recursos.
            if (!workerFinished.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Worker shutdown timeout exceeded");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
