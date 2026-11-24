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
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.aporia.tracking.core.Telemetry;
import software.aporia.tracking.protocol.DecodedPacket;
import software.aporia.tracking.protocol.DecoderRegistry;
import software.aporia.tracking.protocol.TelemetryDecoder;
import software.aporia.tracking.protocol.suntech.SuntechDecoder;
import software.aporia.tracking.protocol.teltonika.TeltonikaAvl;
import software.aporia.tracking.storage.command.DeviceCommandResponseRepository;
import software.aporia.tracking.storage.device.AssignmentRepository;
import software.aporia.tracking.storage.telemetry.LastPositionRepository;
import software.aporia.tracking.storage.telemetry.MessageRepository;
import software.aporia.tracking.storage.telemetry.OutboxRepository;
import software.aporia.tracking.storage.telemetry.PositionRepository;

/**
 * Consome pacotes brutos e coordena decodificação, deduplicação, posições, respostas e eventos em
 * uma transação por pacote.
 */
public class TrackingWorker {

    /** Seleciona o decodificador pela versão que acompanha os bytes brutos. */
    private final DecoderRegistry decoderRegistry;

    /** Fonte de conexões JDBC usadas pela instância. */
    private final DataSource dataSource;

    /** Persiste a mensagem e detecta o conteúdo já processado. */
    private final MessageRepository messageRepository;
    /** Resolve o ativo associado no instante da leitura. */
    private final AssignmentRepository assignmentRepository;
    /** Grava cada leitura válida no histórico geográfico. */
    private final PositionRepository positionRepository;
    /** Atualiza o marcador de posição mais recente do ativo. */
    private final LastPositionRepository lastPositionRepository;
    /** Grava o evento na mesma transação que a posição. */
    private final OutboxRepository outboxRepository;

    /** Restringe a seleção a um tenant quando configurado. */
    private final UUID tenantFilter;

    /** Registra eventos e falhas associados a esta classe. */
    private static final Logger log
            = LoggerFactory.getLogger(TrackingWorker.class);

    /** Persiste respostas de comando vinculadas à recepção do quadro. */
    private final DeviceCommandResponseRepository responseRepository;

    /**
     * Cria um consumidor de pacotes para todos os tenants com os decodificadores padrão.
     *
     * @param dataSource fonte de conexões JDBC para a operação
     */
    public TrackingWorker(DataSource dataSource) {
        this(dataSource, null);
    }

    /**
     * Permite restringir a seleção de pacotes a um tenant.
     *
     * @param dataSource fonte de conexões JDBC para a operação
     * @param tenantFilter tenant a processar, ou nulo para consumir a fila de todos
     */
    public TrackingWorker(DataSource dataSource, UUID tenantFilter) {
        this(dataSource, tenantFilter, defaultDecoderRegistry());
    }

    /**
     * Permite escolher o registro de decodificadores que interpretará os pacotes da fila.
     *
     * @param dataSource fonte de conexões JDBC para a operação
     * @param tenantFilter tenant a processar, ou nulo para consumir a fila de todos
     * @param decoderRegistry decodificadores disponíveis ao worker
     */
    public TrackingWorker(
            DataSource dataSource,
            UUID tenantFilter,
            DecoderRegistry decoderRegistry
    ) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource);
        this.tenantFilter = tenantFilter;
        this.decoderRegistry
                = java.util.Objects.requireNonNull(decoderRegistry);

        this.messageRepository = new MessageRepository();
        this.assignmentRepository = new AssignmentRepository();
        this.positionRepository = new PositionRepository();
        this.lastPositionRepository = new LastPositionRepository();
        this.outboxRepository = new OutboxRepository();
        this.responseRepository = new DeviceCommandResponseRepository();
    }

    /**
     * Registra as versões Suntech e Teltonika disponíveis ao iniciar o consumidor.
     *
     * @return registro das versões de decodificação disponíveis
     */
    private static DecoderRegistry defaultDecoderRegistry() {
        return new DecoderRegistry(List.of(new SuntechDecoder(), new TeltonikaAvl()));
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
                ProcessingResult result
                        = processInTransaction(connection);

                connection.commit();

                logResult(result);

                return result.status()
                        != ProcessingResult.Status.EMPTY;

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
     *
     * @param connection conexão JDBC usada pelo chamador
     * @return resultado a publicar após o commit
     * @throws SQLException se a operação JDBC falhar
     */
    private ProcessingResult processInTransaction(
            Connection connection
    ) throws SQLException {

        // SKIP LOCKED permite que cada worker reserve um pacote livre, respeitando o prazo da próxima tentativa.
        String selectSql = """
            SELECT
                rp.id,
                rp.tenant_id,
                rp.device_id,
                rp.payload,
                rp.payload_hash,
                rp.decoder_version,
                rp.received_at,
                d.protocol, d.identity AS registered_identity
            FROM raw_packets rp
            JOIN devices d
              ON d.id = rp.device_id
              AND d.tenant_id = rp.tenant_id
            WHERE rp.status = 'pending'
              AND rp.available_at <= clock_timestamp()
              AND (?::uuid IS NULL OR rp.tenant_id = ?::uuid)
              AND rp.decoder_version = ANY (?::varchar[])
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
        String protocol;
        String registeredIdentity;

        Instant receivedAt;

        try (PreparedStatement statement
                = connection.prepareStatement(selectSql)) {

            // Configurar os parâmetros ANTES da consulta.
            statement.setObject(1, tenantFilter);
            statement.setObject(2, tenantFilter);
            statement.setArray(3, connection.createArrayOf("varchar", decoderRegistry.versions().toArray(String[]::new)));

            // Executar a consulta depois de configurar.
            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    return ProcessingResult.empty();
                }

                packetId = result.getObject("id", UUID.class);
                tenantId = result.getObject("tenant_id", UUID.class);
                deviceId = result.getObject("device_id", UUID.class);

                payload = result.getBytes("payload");

                storedHash = result.getString("payload_hash");
                decoderVersion = result.getString("decoder_version");
                protocol = result.getString("protocol");
                registeredIdentity = result.getString("registered_identity");

                receivedAt = result.getTimestamp(
                        "received_at"
                ).toInstant();
            }
        }

        // 2. Verificar a versão do decoder.
        TelemetryDecoder decoder = decoderRegistry.get(decoderVersion);

        // 3. Validar integridade e determinar os processamentos.
        DecodedPacket decoded;
        try {
            validateHash(payload, storedHash);
            if (!protocol.equals(decoder.protocolId())) throw new InvalidPacketException("DEVICE_PROTOCOL_MISMATCH");
            try {
                decoded = decoder.decodePacket(payload, registeredIdentity);
            } catch (IllegalArgumentException e) {
                throw new InvalidPacketException("INVALID_PROTOCOL_FRAME", e);
            }
            if (!registeredIdentity.equals(decoded.identity())) {
                throw new InvalidPacketException("DEVICE_IDENTITY_MISMATCH");
            }
        } catch (InvalidPacketException e) {

            rejectPacket(
                    connection,
                    packetId,
                    e.getErrorCode()
            );

            return ProcessingResult.rejected(
                    packetId,
                    e.getErrorCode(),
                    1
            );
        }

        // A resposta pertence ao pacote mesmo quando a posição ainda aguarda associação ao ativo.
        if (decoded.responseType() != null) {
            responseRepository.save(
                    connection,
                    tenantId,
                    deviceId,
                    packetId,
                    protocol,
                    decoded.responseType(),
                    payload
            );
        }

        // 4. Registrar a mensagem.
        // Se o conteúdo já existir, o método retorna null.
        // Criar um ponto de recuperação antes da persistência.
        // O savepoint permite desfazer mensagem e posições e ainda gravar a retentativa na mesma transação.
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

            for (int index = 0; messageId != null && index < decoded.readings().size(); index++) {
                Telemetry telemetry = decoded.readings().get(index);
                if (!telemetry.hasPosition()) continue;

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
                        messageId, index,
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
            return ProcessingResult.processed(packetId);

        } catch (RetryableProcessingException e) {

            // Desfazer gravações parciais.
            connection.rollback(savepoint);

            // Registrar a falha e agendar nova tentativa.
            return scheduleRetry(
                    connection,
                    packetId,
                    e.getErrorCode()
            );

        }

    }

    /**
     * Verifica se o conteúdo corresponde ao hash SHA-256 armazenado na
     * ingestão.
     *
     * @param payload bytes do conteúdo da mensagem
     * @param storedHash resumo SHA-256 do conteúdo recebido
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
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param packetId identificador da recepção bruta
     * @param errorCode código que identifica o motivo da falha
     * @throws SQLException se a operação JDBC falhar
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
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param packetId identificador da recepção bruta
     * @throws SQLException se a operação JDBC falhar
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

    /**
     * Incrementa a tentativa na linha bloqueada e agenda a próxima execução ou encerra o pacote
     * ao atingir o limite.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param packetId identificador da recepção bruta
     * @param errorCode código que identifica o motivo da falha
     * @return resultado a publicar após o commit
     * @throws SQLException se a operação JDBC falhar
     */
    private ProcessingResult scheduleRetry(
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

            return ProcessingResult.rejected(
                    packetId,
                    errorCode,
                    attempts
            );
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
        return ProcessingResult.retryScheduled(
                packetId,
                errorCode,
                attempts,
                delay.toSeconds()
        );
    }

    /**
     * Emite o diagnóstico de acordo com o resultado confirmado pelo processamento.
     *
     * @param result resultado da transação de processamento
     */
    private void logResult(ProcessingResult result) {

        switch (result.status()) {

            case EMPTY -> {
                // Fila vazia: não gerar logs.
            }

            case PROCESSED ->
                log.info(
                        "Packet processed: packetId={}",
                        result.packetId()
                );

            case REJECTED ->
                log.warn(
                        "Packet rejected: packetId={}, errorCode={}, attempts={}",
                        result.packetId(),
                        result.errorCode(),
                        result.attempts()
                );

            case RETRY_SCHEDULED ->
                log.warn(
                        "Packet retry scheduled: packetId={}, errorCode={}, "
                        + "attempts={}, delaySeconds={}",
                        result.packetId(),
                        result.errorCode(),
                        result.attempts(),
                        result.retryDelaySeconds()
                );
        }
    }

}
