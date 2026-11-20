package software.aporia.tracking.worker;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.sql.DataSource;

import software.aporia.tracking.core.InvalidPacketException;
import software.aporia.tracking.core.Telemetry;
import software.aporia.tracking.protocol.SuntechDecoder;
import software.aporia.tracking.storage.AssignmentRepository;
import software.aporia.tracking.storage.LastPositionRepository;
import software.aporia.tracking.storage.MessageRepository;
import software.aporia.tracking.storage.OutboxRepository;
import software.aporia.tracking.storage.PositionRepository;

public class TrackingWorker {

    private static final String SUPPORTED_DECODER = "suntech-ascii-v3";

    private final DataSource dataSource;
    private final SuntechDecoder decoder;

    private final MessageRepository messageRepository;
    private final AssignmentRepository assignmentRepository;
    private final PositionRepository positionRepository;
    private final LastPositionRepository lastPositionRepository;
    private final OutboxRepository outboxRepository;

    private final UUID tenantFilter;

    public TrackingWorker(DataSource dataSource) {
        this(dataSource, null);
    }

    public TrackingWorker(DataSource dataSource, UUID tenantFilter) {
        this.dataSource = dataSource;
        this.tenantFilter = tenantFilter;
        this.decoder = new SuntechDecoder();

        this.messageRepository = new MessageRepository();
        this.assignmentRepository = new AssignmentRepository();
        this.positionRepository = new PositionRepository();
        this.lastPositionRepository = new LastPositionRepository();
        this.outboxRepository = new OutboxRepository();
    }

    /**
     * Processa no máximo um pacote pendente.
     *
     * @return true se um pacote foi tratado; false se não havia pacotes
     * disponíveis.
     */
    public boolean processOne() {

        try (Connection connection = dataSource.getConnection()) {

            connection.setAutoCommit(false);

            try {
                boolean handled = processInTransaction(connection);

                connection.commit();

                return handled;

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
                    "Worker database operation failed", e
            );
        }
    }

    /**
     * Executa todo o processamento utilizando a mesma transação JDBC.
     */
    private boolean processInTransaction(
            Connection connection
    ) throws SQLException {

        // 1. Selecionar um pacote pendente.
        String selectSql = """
            SELECT
                rp.id,
                rp.tenant_id,
                rp.device_id,
                rp.payload,
                rp.payload_hash,
                rp.decoder_version,
                rp.received_at,
                d.identity AS registered_identity
            FROM raw_packets rp
            JOIN devices d
              ON d.id = rp.device_id
              AND d.tenant_id = rp.tenant_id
            WHERE rp.status = 'pending'
              AND rp.available_at <= clock_timestamp()
              AND (?::uuid IS NULL OR rp.tenant_id = ?::uuid)
            ORDER BY rp.received_at, rp.id
            FOR UPDATE OF rp SKIP LOCKED
            LIMIT 1
            """;

        UUID packetId;
        UUID tenantId;
        UUID deviceId;

        byte[] payload;

        String storedHash;
        String decoderVersion;
        String registeredIdentity;

        Instant receivedAt;

        try (PreparedStatement statement
                = connection.prepareStatement(selectSql)) {

            // Configurar os parâmetros ANTES da consulta.
            statement.setObject(1, tenantFilter);
            statement.setObject(2, tenantFilter);

            // Executar a consulta depois de configurar.
            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    return false;
                }

                packetId = result.getObject("id", UUID.class);
                tenantId = result.getObject("tenant_id", UUID.class);
                deviceId = result.getObject("device_id", UUID.class);

                payload = result.getBytes("payload");

                storedHash = result.getString("payload_hash");
                decoderVersion = result.getString("decoder_version");
                registeredIdentity = result.getString("registered_identity");

                receivedAt = result.getTimestamp(
                        "received_at"
                ).toInstant();
            }
        }

        // 2. Verificar a versão do decoder.
        if (!SUPPORTED_DECODER.equals(decoderVersion)) {
            throw new IllegalStateException(
                    "DECODER_UNAVAILABLE: " + decoderVersion
            );
        }

        // 3. Validar o pacote e decodificar.
        Telemetry telemetry;

        try {
            validateHash(payload, storedHash);

            try {
                telemetry = decoder.decode(payload);
            } catch (IllegalArgumentException e) {
                throw new InvalidPacketException(
                        "INVALID_PROTOCOL_FRAME", e
                );
            }

            if (!registeredIdentity.equals(
                    telemetry.deviceIdentity())) {

                throw new InvalidPacketException(
                        "DEVICE_IDENTITY_MISMATCH"
                );
            }

        } catch (InvalidPacketException e) {

            // Erro permanente de validação.
            rejectPacket(
                    connection,
                    packetId,
                    e.getErrorCode()
            );

            return true;
        }

        // 4. Registrar a mensagem.
        // Se o conteúdo já existir, o método retorna null.
        // Criar um ponto de recuperação antes da persistência.
        Savepoint savepoint = connection.setSavepoint(
                "processing_start"
        );

        try {
            // Registrar mensagem e detectar duplicidade.
            UUID messageId = messageRepository.save(
                    connection,
                    tenantId,
                    deviceId,
                    packetId,
                    storedHash
            );

            if (messageId != null && telemetry.hasPosition()) {

                UUID assetId = assignmentRepository.findAssetId(
                        connection,
                        tenantId,
                        deviceId,
                        telemetry.recordedAt()
                ).orElseThrow(() -> new RetryableProcessingException(
                        "ASSIGNMENT_NOT_FOUND"
                ));

                UUID positionId = positionRepository.save(
                        connection,
                        tenantId,
                        assetId,
                        deviceId,
                        messageId,
                        telemetry,
                        receivedAt
                );

                lastPositionRepository.update(
                        connection,
                        tenantId,
                        assetId,
                        positionId,
                        telemetry.recordedAt(),
                        receivedAt
                );

                outboxRepository.savePositionEvent(
                        connection,
                        tenantId,
                        assetId,
                        positionId
                );
            }

            markProcessed(connection, packetId);

            return true;

        } catch (RetryableProcessingException e) {

            // Desfazer gravações parciais.
            connection.rollback(savepoint);

            // Registrar a falha e agendar nova tentativa.
            scheduleRetry(
                    connection,
                    packetId,
                    e.getErrorCode()
            );

            return true;
        }

    }

    /**
     * Verifica se o conteúdo corresponde ao hash SHA-256 armazenado na
     * ingestão.
     */
    private void validateHash(
            byte[] payload,
            String storedHash
    ) {

        final byte[] calculatedHash;

        try {
            MessageDigest digest
                    = MessageDigest.getInstance("SHA-256");

            calculatedHash = digest.digest(payload);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 unavailable", e
            );
        }

        String calculatedHex = HexFormat.of()
                .formatHex(calculatedHash);

        if (!calculatedHex.equals(storedHash)) {
            throw new InvalidPacketException(
                    "HASH_MISMATCH"
            );
        }
    }

    /**
     * Registra uma rejeição permanente.
     *
     * A atualização participa da transação atual.
     */
    private void rejectPacket(
            Connection connection,
            UUID packetId,
            String errorCode
    ) throws SQLException {

        String sql = """
            UPDATE raw_packets
            SET status = 'rejected',
                error_code = ?,
                attempts = attempts + 1,
                processed_at = clock_timestamp()
            WHERE id = ?
              AND status = 'pending'
            """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setString(1, errorCode);
            statement.setObject(2, packetId);

            int updated = statement.executeUpdate();

            if (updated != 1) {
                throw new SQLException(
                        "Failed to reject packet: " + packetId
                );
            }
        }
    }

    /**
     * Confirma o processamento do pacote.
     *
     * Deve ser chamado somente após concluir todas as operações de
     * persistência.
     */
    private void markProcessed(
            Connection connection,
            UUID packetId
    ) throws SQLException {

        String sql = """
            UPDATE raw_packets
            SET status = 'processed',
                error_code = NULL,
                processed_at = clock_timestamp(),
                attempts = attempts + 1
            WHERE id = ?
              AND status = 'pending'
            """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setObject(1, packetId);

            int updated = statement.executeUpdate();

            if (updated != 1) {
                throw new SQLException(
                        "Failed to process packet: " + packetId
                );
            }
        }
    }

    private void scheduleRetry(
            Connection connection,
            UUID packetId,
            String errorCode
    ) throws SQLException {

        int attempts;

        // A linha já está bloqueada pela transação atual.
        String selectSql = """
            SELECT attempts
            FROM raw_packets
            WHERE id = ?
              AND status = 'pending'
            """;

        try (PreparedStatement statement
                = connection.prepareStatement(selectSql)) {

            statement.setObject(1, packetId);

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    throw new SQLException(
                            "Pending packet not found: " + packetId
                    );
                }

                attempts = result.getInt("attempts") + 1;
            }
        }

        if (attempts >= RetryPolicy.MAX_ATTEMPTS) {

            String rejectSql = """
                UPDATE raw_packets
                SET status = 'rejected',
                    error_code = ?,
                    attempts = ?,
                    processed_at = clock_timestamp()
                WHERE id = ?
                  AND status = 'pending'
                """;

            try (PreparedStatement statement
                    = connection.prepareStatement(rejectSql)) {

                statement.setString(1, errorCode);
                statement.setInt(2, attempts);
                statement.setObject(3, packetId);

                if (statement.executeUpdate() != 1) {
                    throw new SQLException(
                            "Failed to reject packet: " + packetId
                    );
                }
            }

            return;
        }

        // Calcular o intervalo antes da próxima tentativa.
        var delay = RetryPolicy.delayForAttempt(attempts);

        String retrySql = """
            UPDATE raw_packets
            SET attempts = ?,
                error_code = ?,
                available_at = clock_timestamp()
                               + (? * INTERVAL '1 second')
            WHERE id = ?
              AND status = 'pending'
            """;

        try (PreparedStatement statement
                = connection.prepareStatement(retrySql)) {

            statement.setInt(1, attempts);
            statement.setString(2, errorCode);
            statement.setLong(3, delay.toSeconds());
            statement.setObject(4, packetId);

            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "Failed to schedule retry: " + packetId
                );
            }
        }
    }

}
