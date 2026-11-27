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

/**
 * Revalida o dispositivo habilitado e persiste o quadro bruto na mesma transação.
 *
 * <p>A conexão vem do pool e a transação pertence a esta classe: o commit só acontece depois da revalidação e
 * da inserção, e o UUID retornado já está confirmado. O tenant e o dispositivo da sessão são obrigatórios e
 * participam do {@code WHERE}, impedindo que uma identidade válida grave em outro escopo. A leitura do
 * cadastro usa {@code FOR SHARE} para mantê-lo habilitado até o commit.</p>
 */
public class JdbcPacketIngestion {

    /** Fonte de conexões JDBC usadas pela instância. */
    private final DataSource dataSource;

    /**
     * Recebe o pool usado para revalidar o cadastro e confirmar a ingestão em uma transação.
     *
     * @param dataSource fonte de conexões JDBC para a operação
     * @throws NullPointerException se a fonte de conexões for nula
     */
    public JdbcPacketIngestion(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /**
     * Persiste o quadro no tenant e dispositivo da sessão e retorna seu UUID após o commit.
     *
     * <p>O status inicial é {@code pending}, com hash SHA-256 e correlação novos. Se a revalidação ou a
     * inserção falhar, a transação é desfeita e a exceção original é propagada; falhas de rollback são
     * anexadas como suprimidas. {@link IllegalArgumentException} de dispositivo desconhecido ou desabilitado
     * não é convertida.</p>
     *
     * @param frame quadro recebido para interpretação ou ingestão
     * @param expectedTenant identificador esperado pela sessão para restringir a consulta
     * @param expectedDevice identificador esperado pela sessão para restringir a consulta
     * @return UUID da nova recepção
     * @throws NullPointerException     se o quadro ou um identificador esperado for nulo
     * @throws IllegalArgumentException se o dispositivo não existir, estiver
     *                                  desabilitado ou fora do escopo esperado
     * @throws IllegalStateException    se a transação ou o acesso JDBC falhar
     */
    public UUID accept(ReceivedFrame frame, UUID expectedTenant, UUID expectedDevice) {
        java.util.Objects.requireNonNull(expectedTenant, "expectedTenant");
        java.util.Objects.requireNonNull(expectedDevice, "expectedDevice");
        try (Connection connection = dataSource.getConnection()) {
            // Revalidação e inserção formam uma unidade: o UUID só é devolvido após
            // o commit.
            connection.setAutoCommit(false);

            try {
                UUID packetId = acceptInTransaction(connection, frame, expectedTenant, expectedDevice);

                connection.commit();
                return packetId;
            } catch (SQLException | RuntimeException e) {
                // Desfaz a inserção e libera o bloqueio compartilhado do cadastro.
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
     *
     * <p>O {@code FOR SHARE} mantém o cadastro habilitado e no escopo da sessão até o commit, impedindo
     * desabilitação concorrente. O hash SHA-256 é calculado sobre os mesmos bytes que o worker reinterpretará
     * depois. A inserção grava status {@code pending} e uma correlação nova; o chamador confirma a
     * transação.</p>
     *
     * @param connection conexão com transação aberta pelo chamador
     * @param frame quadro completo recebido pelo gateway
     * @param expectedTenant tenant obrigatório da sessão
     * @param expectedDevice dispositivo obrigatório da sessão
     * @return identificador da recepção, confirmado pelo chamador ao concluir a transação
     * @throws SQLException             se a consulta ou a gravação falhar
     * @throws IllegalArgumentException se o dispositivo não existir, estiver
     *                                  desabilitado ou fora do escopo esperado
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
