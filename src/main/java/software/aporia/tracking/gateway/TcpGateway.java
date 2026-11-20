package software.aporia.tracking.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.aporia.tracking.core.ReceivedFrame;
import software.aporia.tracking.protocol.SuntechProtocol;
import software.aporia.tracking.storage.Database;
import software.aporia.tracking.storage.JdbcPacketIngestion;

public class TcpGateway {

    private static final Logger log
            = LoggerFactory.getLogger(TcpGateway.class);

    private static final int PORT = Integer.parseInt(
            System.getenv().getOrDefault("TRACKING_TCP_PORT", "5500")
    );
    private static final int READ_TIMEOUT_MS = 30_000;

    public static void main(String[] args) throws IOException {

        try (
                var dataSource = Database.createDataSource(); ServerSocket server = new ServerSocket(
                PORT,
                50,
                InetAddress.getByName("0.0.0.0")
        ); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            {

                JdbcPacketIngestion ingestion
                        = new JdbcPacketIngestion(dataSource);

                log.info(
                        "Tracking TCP Gateway listening on 0.0.0.0:" + PORT
                );

                while (!Thread.currentThread().isInterrupted()) {
                    Socket client = server.accept();

                    executor.submit(() -> handleClient(client, ingestion));
                }
            }
        }
    }

    private static void handleClient(Socket client, JdbcPacketIngestion ingestion) {
        log.debug(
                "TCP connection accepted: remote={}",
                client.getRemoteSocketAddress()
        );

        SuntechProtocol protocol = new SuntechProtocol();

        try (client; InputStream input = client.getInputStream()) {

            client.setSoTimeout(READ_TIMEOUT_MS);

            byte[] buffer = new byte[4096];
            int bytesRead;

            while ((bytesRead = input.read(buffer)) != -1) {

                byte[] received = java.util.Arrays.copyOf(
                        buffer,
                        bytesRead
                );

                for (byte[] frame : protocol.receive(received)) {
                    String identity = protocol.identify(frame);

                    ReceivedFrame receivedFrame = new ReceivedFrame("suntech", identity, frame);

                    var packetId = ingestion.accept(receivedFrame);

                    log.info(
                            "Packet persisted: id=%s, identity=%s, bytes=%d%n",
                            packetId,
                            identity,
                            frame.length
                    );
                }
            }

        } catch (SocketTimeoutException e) {
            log.error("TCP session timed out");

        } catch (IllegalArgumentException e) {
            log.error(
                    "Invalid Suntech frame: " + e.getMessage()
            );

        } catch (IOException e) {
            log.error(
                    "TCP connection error: " + e.getMessage()
            );
        } catch (IllegalStateException e) {
            log.error(
                    "Packet persistence failed: " + e.getMessage()
            );
        }
    }

}
