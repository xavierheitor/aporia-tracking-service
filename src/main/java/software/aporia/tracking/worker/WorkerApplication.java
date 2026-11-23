package software.aporia.tracking.worker;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.aporia.tracking.storage.Database;

public class WorkerApplication {

    private static final Logger log
            = LoggerFactory.getLogger(WorkerApplication.class);

    @SuppressWarnings("SleepWhileInLoop") // Intervalos de polling e de recuperação após falha.
    public static void main(String[] args) {

        AtomicBoolean running = new AtomicBoolean(true);
        var workerFinished = new java.util.concurrent.CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    log.info("Shutdown requested for Tracking Worker");
                    running.set(false);
                    try {
                        if (!workerFinished.await(
                                10, java.util.concurrent.TimeUnit.SECONDS)) {
                            log.warn("Worker shutdown timeout exceeded");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "tracking-worker-shutdown")
        );

        try {
            try (var dataSource = Database.createDataSource()) {

                TrackingWorker worker = new TrackingWorker(dataSource);

                log.info("Aporia Tracking Worker started");

                while (running.get()) {

                    try {
                        boolean handled = worker.processOne();

                        if (!handled) {
                            Thread.sleep(1000);
                        }

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;

                    } catch (RuntimeException e) {

                        log.error("Worker processing failed", e);

                        if (!running.get()) {
                            break;
                        }

                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                log.info("Aporia Tracking Worker stopped");
            }

        } finally {
            workerFinished.countDown();
        }

    }
}
