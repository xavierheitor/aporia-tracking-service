
package software.aporia.tracking.worker;

import software.aporia.tracking.storage.Database;

public class WorkerApplication {

    public static void main(String[] args) {

        try (var dataSource = Database.createDataSource()) {

            TrackingWorker worker = new TrackingWorker(dataSource);

            boolean processed = worker.processOne();

            System.out.println(
                processed
                    ? "One packet processed"
                    : "No pending packets"
            );
        }
    }
}
