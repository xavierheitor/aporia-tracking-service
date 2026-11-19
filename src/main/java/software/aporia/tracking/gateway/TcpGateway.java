package software.aporia.tracking.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executors;

import software.aporia.tracking.core.ReceivedFrame;
import software.aporia.tracking.protocol.SuntechProtocol;
import software.aporia.tracking.storage.JdbcPacketIngestion;

public class TcpGateway {

    private static final int PORT = 5500;
    private static final int READ_TIMEOUT_MS = 30_000;

    public static void main(String[] args) throws IOException {
        String dbUrl = System.getenv().getOrDefault("TRACKING_DB_URL", "jdbc:postgresql://localhost:55432/aporia_tracking_v2");
        String dbUser = System.getenv().getOrDefault(
                "TRACKING_DB_USER",
                "tracking"
        );

        String dbPassword = System.getenv("TRACKING_DB_PASSWORD");

        if (dbPassword == null || dbPassword.isBlank()) {
            throw new IllegalStateException(
                    "TRACKING_DB_PASSWORD is required"
            );
        }

        JdbcPacketIngestion ingestion = new JdbcPacketIngestion(
                dbUrl,
                dbUser,
                dbPassword
        );
        try (
                ServerSocket server = new ServerSocket(
                        PORT,
                        50,
                        InetAddress.getLoopbackAddress()
                ); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            {
                System.out.println(
                        "Tracking TCP Gateway listening on 127.0.0.1:" + PORT
                );

                while (!Thread.currentThread().isInterrupted()) {
                    Socket client = server.accept();

                    executor.submit(() -> handleClient(client, ingestion));
                }
            }
        }
    }

    private static void handleClient(Socket client, JdbcPacketIngestion ingestion) {

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

                    System.out.printf(
                            "Packet persisted: id=%s, identity=%s, bytes=%d%n",
                            packetId,
                            identity,
                            frame.length
                    );
                }
            }

        } catch (SocketTimeoutException e) {
            System.out.println("TCP session timed out");

        } catch (IllegalArgumentException e) {
            System.err.println(
                    "Invalid Suntech frame: " + e.getMessage()
            );

        } catch (IOException e) {
            System.err.println(
                    "TCP connection error: " + e.getMessage()
            );
        } catch (IllegalStateException e) {
            System.err.println(
                    "Packet persistence failed: " + e.getMessage()
            );
        }
    }

}
