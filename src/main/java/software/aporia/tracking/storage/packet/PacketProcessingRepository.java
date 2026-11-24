package software.aporia.tracking.storage.packet;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;

import software.aporia.tracking.core.Telemetry;
import software.aporia.tracking.storage.command.DeviceCommandResponseRepository;
import software.aporia.tracking.storage.device.AssignmentRepository;
import software.aporia.tracking.storage.telemetry.LastPositionRepository;
import software.aporia.tracking.storage.telemetry.MessageRepository;
import software.aporia.tracking.storage.telemetry.OutboxRepository;
import software.aporia.tracking.storage.telemetry.PositionRepository;

/**
 * Reserva um pacote e reúne sua persistência em uma única transação.
 *
 * <p>O worker fornece as decisões de processamento por callback. A conexão JDBC,
 * os bloqueios, o savepoint e o commit permanecem nesta camada. Os repositórios
 * de mensagem, posição, resposta e outbox compartilham a mesma conexão.</p>
 */
public final class PacketProcessingRepository {
    /** Pool que fornece a conexão exclusiva de cada ciclo de processamento. */
    private final DataSource dataSource;
    private final MessageRepository messages = new MessageRepository();
    private final AssignmentRepository assignments = new AssignmentRepository();
    private final PositionRepository positions = new PositionRepository();
    private final LastPositionRepository lastPositions = new LastPositionRepository();
    private final OutboxRepository outbox = new OutboxRepository();
    private final DeviceCommandResponseRepository responses = new DeviceCommandResponseRepository();

    /**
     * @param dataSource pool usado somente pela camada de persistência
     */
    public PacketProcessingRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    /**
     * Dados do pacote bloqueado, sem expor ResultSet ou objetos JDBC ao worker.
     *
     * @param id recepção bruta
     * @param tenantId tenant proprietário
     * @param deviceId dispositivo que originou a recepção
     * @param payload bytes persistidos na ingestão
     * @param payloadHash hash SHA-256 esperado
     * @param decoderVersion versão gravada na recepção
     * @param receivedAt instante de recepção
     * @param protocol protocolo do cadastro
     * @param registeredIdentity identidade do cadastro
     * @param attempts tentativas confirmadas antes deste processamento
     */
    public record Packet(UUID id, UUID tenantId, UUID deviceId, byte[] payload,
                         String payloadHash, String decoderVersion, Instant receivedAt,
                         String protocol, String registeredIdentity, int attempts) {
        /** Preserva os bytes da reserva mesmo se o chamador modificar seu array. */
        public Packet {
            payload = Arrays.copyOf(payload, payload.length);
        }

        /** @return cópia dos bytes recebidos, sem expor o array guardado na reserva */
        @Override
        public byte[] payload() {
            return Arrays.copyOf(payload, payload.length);
        }
    }

    /**
     * Reserva no máximo um pacote elegível e só devolve o resultado após o commit.
     *
     * @param tenantFilter tenant a consumir, ou nulo para o consumidor global
     * @param decoderVersions versões que este consumidor sabe interpretar
     * @param process decisões executadas com a reserva ainda bloqueada; o contexto
     *                não deve ser guardado nem usado fora desta chamada
     * @param <T> resultado produzido pelo processamento
     * @return vazio se não houver pacote elegível
     */
    public <T> Optional<T> processNext(UUID tenantFilter, Set<String> decoderVersions,
                                      Function<Processing, T> process) {
        Objects.requireNonNull(process);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var packet = claim(connection, tenantFilter, decoderVersions);
                Optional<T> result = packet.map(value ->
                        Objects.requireNonNull(process.apply(new Processing(connection, value))));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            }
        } catch (SQLException error) {
            throw databaseFailure(error);
        }
    }

    /** Seleciona sem esperar por pacotes que outro consumidor já bloqueou. */
    private Optional<Packet> claim(Connection connection, UUID tenantFilter,
                                   Set<String> decoderVersions) throws SQLException {
        String sql = """
                SELECT rp.id, rp.tenant_id, rp.device_id, rp.payload, rp.payload_hash,
                       rp.decoder_version, rp.received_at, rp.attempts,
                       d.protocol, d.identity AS registered_identity
                FROM raw_packets rp
                JOIN devices d ON d.id=rp.device_id AND d.tenant_id=rp.tenant_id
                WHERE rp.status='pending' AND rp.available_at <= clock_timestamp()
                  AND (?::uuid IS NULL OR rp.tenant_id=?::uuid)
                  AND rp.decoder_version=ANY (?::varchar[])
                ORDER BY rp.received_at, rp.id
                FOR UPDATE OF rp SKIP LOCKED
                LIMIT 1
                """;
        var versions = connection.createArrayOf("varchar", decoderVersions.toArray(String[]::new));
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantFilter);
            statement.setObject(2, tenantFilter);
            statement.setArray(3, versions);
            try (var row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new Packet(row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class), row.getObject("device_id", UUID.class),
                        row.getBytes("payload"), row.getString("payload_hash"),
                        row.getString("decoder_version"),
                        row.getObject("received_at", OffsetDateTime.class).toInstant(),
                        row.getString("protocol"), row.getString("registered_identity"), row.getInt("attempts")));
            }
        } finally {
            versions.free();
        }
    }

    /**
     * Operações disponíveis apenas durante o processamento do pacote reservado.
     * O escopo é capturado na reserva: o worker não fornece outro tenant ou dispositivo
     * para as mutações. Nenhum método confirma a transação individualmente.
     */
    public final class Processing {
        private final Connection connection;
        private final Packet packet;

        private Processing(Connection connection, Packet packet) {
            this.connection = connection;
            this.packet = packet;
        }

        /** @return dados da recepção bloqueada nesta transação */
        public Packet packet() {
            return packet;
        }

        /**
         * Persiste a resposta antes do savepoint da telemetria, sem presumir correlação.
         *
         * @param responseType tipo reconhecido pelo decoder
         */
        public void saveResponse(String responseType) {
            execute(() -> responses.save(connection, packet.tenantId(), packet.deviceId(),
                    packet.id(), packet.protocol(), responseType, packet.payload()));
        }

        /**
         * Deduplica a mensagem e grava posições, última posição e outbox juntas.
         *
         * @param readings leituras na ordem original do pacote
         * @return falso se faltar associação ao ativo; nesse caso, desfaz somente
         *         os efeitos de telemetria, preservando a resposta já registrada
         */
        public boolean saveTelemetry(List<Telemetry> readings) {
            return execute(() -> {
                // O bloqueio da reserva foi obtido antes do savepoint e continua válido
                // após o rollback parcial. Assim, outro worker não assume este pacote.
                var savepoint = connection.setSavepoint("processing_start");
                UUID messageId = messages.save(connection, packet.tenantId(), packet.deviceId(),
                        packet.id(), packet.payloadHash());
                for (int index = 0; messageId != null && index < readings.size(); index++) {
                    Telemetry telemetry = readings.get(index);
                    if (!telemetry.hasPosition()) continue;
                    var assetId = assignments.findAssetId(connection, packet.tenantId(),
                            packet.deviceId(), telemetry.recordedAt());
                    if (assetId.isEmpty()) {
                        connection.rollback(savepoint);
                        return false;
                    }
                    UUID positionId = positions.save(connection, packet.tenantId(), assetId.get(),
                            packet.deviceId(), messageId, index, telemetry, packet.receivedAt());
                    lastPositions.update(connection, packet.tenantId(), assetId.get(), positionId,
                            telemetry.recordedAt(), packet.receivedAt());
                    outbox.savePositionEvent(connection, packet.tenantId(), assetId.get(), positionId);
                }
                return true;
            });
        }

        /** Marca a conclusão na mesma transação dos efeitos de telemetria. */
        public void markProcessed() {
            finish("processed", null);
        }

        /**
         * Encerra o pacote com falha e incrementa a tentativa.
         *
         * @param errorCode motivo da rejeição
         */
        public void reject(String errorCode) {
            finish("rejected", errorCode);
        }

        /** Confere status e escopo mesmo com a linha já bloqueada pela reserva. */
        private void finish(String status, String errorCode) {
            execute(() -> {
                try (var statement = connection.prepareStatement("""
                        UPDATE raw_packets SET status=?, error_code=?, attempts=attempts+1,
                            processed_at=clock_timestamp()
                        WHERE tenant_id=? AND device_id=? AND id=? AND status='pending'
                        """)) {
                    statement.setString(1, status);
                    statement.setString(2, errorCode);
                    bindScope(statement, 3);
                    requireUpdated(statement.executeUpdate());
                }
                return null;
            });
        }

        /**
         * Mantém o pacote pendente e registra o prazo decidido pela política do worker.
         *
         * @param errorCode motivo da nova tentativa
         * @param delay intervalo até o pacote voltar a ser elegível
         */
        public void scheduleRetry(String errorCode, Duration delay) {
            execute(() -> {
                try (var statement = connection.prepareStatement("""
                        UPDATE raw_packets SET attempts=attempts+1, error_code=?,
                            available_at=clock_timestamp() + (? * INTERVAL '1 second')
                        WHERE tenant_id=? AND device_id=? AND id=? AND status='pending'
                        """)) {
                    statement.setString(1, errorCode);
                    statement.setLong(2, delay.toSeconds());
                    bindScope(statement, 3);
                    requireUpdated(statement.executeUpdate());
                }
                return null;
            });
        }

        private void bindScope(java.sql.PreparedStatement statement, int start) throws SQLException {
            statement.setObject(start, packet.tenantId());
            statement.setObject(start + 1, packet.deviceId());
            statement.setObject(start + 2, packet.id());
        }

        private void requireUpdated(int updated) throws SQLException {
            if (updated != 1) throw new SQLException("Pending packet not found: " + packet.id());
        }
    }

    /** Mantém detalhes JDBC e a causa SQL na camada de persistência. */
    private static <T> T execute(SqlOperation<T> operation) {
        try {
            return operation.run();
        } catch (SQLException error) {
            throw databaseFailure(error);
        }
    }

    private static IllegalStateException databaseFailure(SQLException error) {
        return new IllegalStateException("Worker database operation failed", error);
    }

    /** Operação interna que pode falhar no driver; não faz parte do contrato do worker. */
    @FunctionalInterface
    private interface SqlOperation<T> {
        T run() throws SQLException;
    }
}
