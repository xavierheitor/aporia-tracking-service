package software.aporia.tracking.storage.packet;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.UUID;
import javax.sql.DataSource;

import software.aporia.tracking.core.ReceivedFrame;

/** Revalida o dispositivo habilitado e persiste o quadro bruto na mesma transação. */
public class JdbcPacketIngestion {

    /** Fonte de conexões JDBC usadas pela instância. */
    private final DataSource dataSource;

    /**
     * Recebe o pool usado para revalidar o cadastro e confirmar a ingestão em uma transação.
     *
     * @param dataSource fonte de conexões JDBC para a operação
     */
    public JdbcPacketIngestion(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /**
     * Persiste o quadro no tenant e dispositivo da sessão e retorna seu UUID após o commit.
     *
     * @param frame quadro recebido para interpretação ou ingestão
     * @param expectedTenant identificador esperado pela sessão para restringir a consulta
     * @param expectedDevice identificador esperado pela sessão para restringir a consulta
     * @return UUID da nova recepção
     */
    public UUID accept(ReceivedFrame frame, UUID expectedTenant, UUID expectedDevice) {
        java.util.Objects.requireNonNull(expectedTenant, "expectedTenant");
        java.util.Objects.requireNonNull(expectedDevice, "expectedDevice");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try {
                UUID packetId = acceptInTransaction(connection, frame, expectedTenant, expectedDevice);

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
            throw new IllegalStateException(
                    "Packet ingestion failed", e
            );
        }
    }

    /**
     * Revalida o cadastro e insere o quadro bruto sob o mesmo bloqueio compartilhado.
     * @param connection conexão com transação aberta pelo chamador
     * @param frame quadro completo recebido pelo gateway
     * @param expectedTenant tenant obrigatório da sessão
     * @param expectedDevice dispositivo obrigatório da sessão
     * @return identificador da recepção, confirmado pelo chamador ao concluir a transação
     * @throws SQLException se a consulta ou a gravação falhar
     */
    private UUID acceptInTransaction(
            Connection connection,
            ReceivedFrame frame, UUID expectedTenant, UUID expectedDevice
    ) throws SQLException {
        // FOR SHARE mantém o cadastro habilitado e no escopo da sessão até o commit do pacote.
        String lookupSql = """
            SELECT id, tenant_id
            FROM devices
            WHERE protocol = ?
              AND identity = ?
              AND enabled = true
              AND tenant_id=? AND id=?
            FOR SHARE
            """;

        UUID deviceId;
        UUID tenantId;

        try (PreparedStatement statement
                = connection.prepareStatement(lookupSql)) {

            statement.setString(1, frame.protocol());
            statement.setString(2, frame.identity());
            statement.setObject(3, expectedTenant);
            statement.setObject(4, expectedDevice);

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException(
                            "Unknown or disabled device"
                    );
                }
                deviceId = result.getObject(
                        "id", UUID.class
                );
                tenantId = result.getObject(
                        "tenant_id", UUID.class
                );
            }
        }

        byte[] payload = frame.payload();

        String payloadHash;

        try {
            // O worker recalcula o hash sobre os mesmos bytes antes de interpretar o conteúdo.
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

        try (PreparedStatement statement
                = connection.prepareStatement(insertSql)) {

            statement.setObject(1, packetId);
            statement.setObject(2, tenantId);
            statement.setObject(3, deviceId);
            statement.setBytes(4, payload);
            statement.setString(5, payloadHash);
            statement.setString(
                    6, frame.decoderVersion()
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
