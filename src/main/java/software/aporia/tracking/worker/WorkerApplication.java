
package software.aporia.tracking.worker;

import software.aporia.tracking.storage.Database;

public class WorkerApplication {

    public static void main(String[] args) {

        try (var dataSource = Database.createDataSource()) {

            TrackingWorker worker = new TrackingWorker(dataSource);

            System.out.println("Aporia Tracking Worker started");

            while (!Thread.currentThread().isInterrupted()) {

                try {
                    boolean handled = worker.processOne();

                    if (!handled) {
                        // Evita consultas contínuas quando
                        // não existem pacotes disponíveis.
                        Thread.sleep(1000);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;

                } catch (RuntimeException e) {

                    System.err.println(
                        "Worker processing failed: "
                        + e.getMessage()
                    );

                    // Evita um loop de falhas sem intervalo.
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            System.out.println("Aporia Tracking Worker stopped");
        }
    }
}
