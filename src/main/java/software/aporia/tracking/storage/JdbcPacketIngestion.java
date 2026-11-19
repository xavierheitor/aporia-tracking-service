package software.aporia.tracking.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.UUID;

import software.aporia.tracking.core.ReceivedFrame;

public class JdbcPacketIngestion {

    // Implementation details for JDBC packet ingestion
    private final String url;
    private final String username;
    private final String password;

    public JdbcPacketIngestion(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public UUID accept(ReceivedFrame frame) {
        //try to create a connection to the database and accept the frame
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            // Disable auto-commit for transaction management
            connection.setAutoCommit(false);

            try {
                //if the frame is accepted, commit the transaction and return the packet ID
                UUID packetId = acceptInTransaction(connection, frame);

                connection.commit();
                return packetId;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }

                throw e;
            }

        } catch (SQLException e) {
            // Handle SQL exceptions and wrap them in a runtime exception
            throw new IllegalStateException(
                    "Packet ingestion failed", e
            );
        }
    }

    /**
     * Accepts a received frame within a database transaction. verifies if the
     * device is known and enabled, computes the payload hash and save it in the
     * database.
     *
     * @param connection the database connection
     * @param frame the received frame to be accepted
     * @return the UUID of the accepted packet
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if the device is unknown or disabled
     * @throws IllegalStateException if SHA-256 algorithm is unavailable
     */
    private UUID acceptInTransaction(
            Connection connection,
            ReceivedFrame frame
    ) throws SQLException {
        // Lookup device by protocol and identity
        String lookupSql = """
            SELECT id, tenant_id
            FROM devices
            WHERE protocol = ?
              AND identity = ?
              AND enabled = true
            FOR SHARE
            """;

        UUID deviceId;
        UUID tenantId;

        try (PreparedStatement statement
                = connection.prepareStatement(lookupSql)) {

            statement.setString(1, frame.protocol());
            statement.setString(2, frame.identity());

            try (ResultSet result = statement.executeQuery()) {
                // If no device is found, throw an exception indicating that the device is unknown or disabled
                if (!result.next()) {
                    throw new IllegalArgumentException(
                            "Unknown or disabled device"
                    );
                }
                // Retrieve device ID and tenant ID from the result set
                deviceId = result.getObject(
                        "id", UUID.class
                );
                // Retrieve tenant ID from the result set
                tenantId = result.getObject(
                        "tenant_id", UUID.class
                );
            }
        }

        byte[] payload = frame.payload();

        String payloadHash;

        try {
            // Compute the SHA-256 hash of the payload to ensure data integrity and uniqueness
            MessageDigest digest
                    = MessageDigest.getInstance("SHA-256");

            payloadHash = HexFormat.of().formatHex(
                    digest.digest(payload)
            );

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 unavailable", e
            );
        }

        UUID packetId = UUID.randomUUID();

        String insertSql = """
            INSERT INTO raw_packets (
                id,
                tenant_id,
                device_id,
                payload,
                payload_hash,
                decoder_version,
                status,
                correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

        // Insert the raw packet into the database
        try (PreparedStatement statement
                = connection.prepareStatement(insertSql)) {

            statement.setObject(1, packetId);
            statement.setObject(2, tenantId);
            statement.setObject(3, deviceId);
            statement.setBytes(4, payload);
            statement.setString(5, payloadHash);
            statement.setString(
                    6, "suntech-ascii-v3"
            );
            statement.setString(7, "pending");
            statement.setObject(
                    8, UUID.randomUUID()
            );

            statement.executeUpdate();
        }

        return packetId;
    }
}
