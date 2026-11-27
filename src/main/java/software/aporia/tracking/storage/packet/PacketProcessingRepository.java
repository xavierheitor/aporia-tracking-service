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
 *
 * <p>O consumidor pode restringir a reserva a um tenant ou atuar globalmente e declarar as versões de
 * decodificador que entende. A linha reservada fica bloqueada até o commit; qualquer exceção desfaz a
 * transação inteira, e o chamador só recebe o resultado depois da confirmação.</p>
 */
public final class PacketProcessingRepository {
    /** Pool que fornece a conexão exclusiva de cada ciclo de processamento. */
    private final DataSource dataSource;
    /** Grava a mensagem deduplicada do pacote. */
    private final MessageRepository messages = new MessageRepository();
    /** Resolve a associação ao ativo na data da leitura. */
    private final AssignmentRepository assignments = new AssignmentRepository();
    /** Grava o histórico de posições. */
    private final PositionRepository positions = new PositionRepository();
    /** Avança o marcador da última posição por ativo. */
    private final LastPositionRepository lastPositions = new LastPositionRepository();
    /** Registra os eventos de posição para publicação posterior. */
    private final OutboxRepository outbox = new OutboxRepository();
    /** Persiste respostas de protocolo sem correlação presumida. */
    private final DeviceCommandResponseRepository responses = new DeviceCommandResponseRepository();

    /**
     * Recebe o pool usado exclusivamente pela camada de persistência.
     *
     * @param dataSource pool usado somente pela camada de persistência
     * @throws NullPointerException se a fonte de conexões for nula
     */
    public PacketProcessingRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    /**
     * Dados do pacote bloqueado, sem expor ResultSet ou objetos JDBC ao worker.
     *
     * <p>Os bytes são copiados na construção e em cada chamada de {@link #payload()}, preservando a reserva.
     * O registro também carrega o protocolo e a identidade do cadastro lidos na mesma consulta, para que o
     * processamento não consulte o dispositivo novamente.</p>
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
        /**
         * Preserva os bytes da reserva mesmo se o chamador modificar seu array.
         *
         * @throws NullPointerException se o payload for nulo
         */
        public Packet {
            payload = Arrays.copyOf(payload, payload.length);
        }

        /**
         * Devolve uma cópia dos bytes recebidos.
         *
         * @return bytes do pacote, sem expor o array guardado na reserva
         */
        @Override
        public byte[] payload() {
            return Arrays.copyOf(payload, payload.length);
        }
    }

    /**
     * Reserva no máximo um pacote elegível e só devolve o resultado após o commit.
     *
     * <p>A conexão e a transação pertencem a este método. O callback executa com a linha já bloqueada; se ele
     * lançar qualquer exceção, ou se o commit falhar, a transação é desfeita e o erro original é propagado.
     * Um callback que não chame {@link Processing#markProcessed()}, {@link Processing#reject(String)} ou
     * {@link Processing#scheduleRetry(String, Duration)} deixa o pacote pendente, mas ainda assim confirma os
     * demais efeitos que tiver gravado.</p>
     *
     * @param tenantFilter tenant a consumir, ou nulo para o consumidor global
     * @param decoderVersions versões que este consumidor sabe interpretar
     * @param process decisões executadas com a reserva ainda bloqueada; o contexto
     *                não deve ser guardado nem usado fora desta chamada
     * @param <T> resultado produzido pelo processamento
     * @return vazio se não houver pacote elegível
     * @throws NullPointerException  se o callback ou o conjunto de versões for nulo
     * @throws IllegalStateException se a operação JDBC ou o commit falhar
     */
    public <T> Optional<T> processNext(UUID tenantFilter, Set<String> decoderVersions,
                                      Function<Processing, T> process) {
        Objects.requireNonNull(process);
        try (var connection = dataSource.getConnection()) {
            // A conexão é exclusiva do ciclo; a transação cobre reserva, callback e
            // atualização final do pacote.
            connection.setAutoCommit(false);
            try {
                var packet = claim(connection, tenantFilter, decoderVersions);
                Optional<T> result = packet.map(value ->
                        Objects.requireNonNull(process.apply(new Processing(connection, value))));
                // O resultado só é liberado depois da confirmação efetiva do banco.
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error error) {
                // Desfaz a reserva e todos os efeitos parciais antes de propagar.
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

    /**
     * Seleciona sem esperar por pacotes que outro consumidor já bloqueou.
     *
     * <p>Considera apenas {@code pending} com {@code available_at} vencido, do tenant solicitado (ou de
     * qualquer tenant quando o filtro é nulo) e com versão de decodificador na lista informada. {@code SKIP
     * LOCKED} e o {@code LIMIT 1} fazem cada worker reservar no máximo um pacote sem aguardar concorrentes; a
     * ordem favorece o recebimento mais antigo.</p>
     *
     * @param connection conexão com transação aberta
     * @param tenantFilter tenant a consumir, ou nulo para o consumidor global
     * @param decoderVersions versões aceitas pelo consumidor
     * @return pacote reservado, ou vazio se não houver candidato elegível
     * @throws SQLException se a consulta falhar
     */
    private Optional<Packet> claim(Connection connection, UUID tenantFilter,
                                   Set<String> decoderVersions) throws SQLException {
        // O tenant nulo vira um teste SQL explícito e as versões viram um array
        // tipado para ANY, sem concatenar valores na instrução.
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
                // SKIP LOCKED evita esperar: um pacote já reservado por outro
                // consumidor simplesmente não entra no resultado.
                if (!row.next()) return Optional.empty();
                return Optional.of(new Packet(row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class), row.getObject("device_id", UUID.class),
                        row.getBytes("payload"), row.getString("payload_hash"),
                        row.getString("decoder_version"),
                        row.getObject("received_at", OffsetDateTime.class).toInstant(),
                        row.getString("protocol"), row.getString("registered_identity"), row.getInt("attempts")));
            }
        } finally {
            // O array pertence à conexão e precisa ser liberado antes de devolvê-la.
            versions.free();
        }
    }

    /**
     * Operações disponíveis apenas durante o processamento do pacote reservado.
     * O escopo é capturado na reserva: o worker não fornece outro tenant ou dispositivo
     * para as mutações. Nenhum método confirma a transação individualmente.
     *
     * <p>Os métodos convertem falhas de JDBC em {@link IllegalStateException} e exigem que a linha continue
     * {@code pending}, exceto {@link #saveTelemetry(List)}, que pode desfazer apenas os próprios efeitos por
     * savepoint.</p>
     */
    public final class Processing {
        /** Conexão da transação que mantém o pacote bloqueado. */
        private final Connection connection;
        /** Dados imutáveis do pacote reservado. */
        private final Packet packet;

        /**
         * Associa a conexão da reserva aos dados já bloqueados do pacote.
         *
         * @param connection conexão da transação corrente
         * @param packet dados do pacote reservado
         */
        private Processing(Connection connection, Packet packet) {
            this.connection = connection;
            this.packet = packet;
        }

        /**
         * Devolve os dados da recepção bloqueada nesta transação.
         *
         * @return pacote reservado para processamento
         */
        public Packet packet() {
            return packet;
        }

        /**
         * Persiste a resposta antes do savepoint da telemetria, sem presumir correlação.
         *
         * <p>O comando de origem permanece nulo e o resultado fica {@code unknown}. Se o pacote já tinha
         * resposta, a deduplicação devolve nulo e nada é gravado. Como ocorre antes do savepoint, uma falha
         * posterior de telemetria não remove esta resposta.</p>
         *
         * @param responseType tipo reconhecido pelo decoder
         * @throws IllegalArgumentException se o tipo ou o payload não forem aceitos
         * @throws IllegalStateException     se a gravação JDBC falhar
         */
        public void saveResponse(String responseType) {
            execute(() -> responses.save(connection, packet.tenantId(), packet.deviceId(),
                    packet.id(), packet.protocol(), responseType, packet.payload()));
        }

        /**
         * Deduplica a mensagem e grava posições, última posição e outbox juntas.
         *
         * <p>O savepoint é anterior à mensagem, portanto um rollback parcial remove mensagem, posições,
         * marcador e eventos de telemetria, mas preserva a resposta gravada por {@link
         * #saveResponse(String)}. Quando o conteúdo já foi registrado, a mensagem não é inserida, o laço não
         * grava posições e o método retorna verdadeiro. Quando falta associação ao ativo em alguma leitura,
         * desfaz o savepoint e retorna falso sem lançar exceção.</p>
         *
         * @param readings leituras na ordem original do pacote
         * @return falso se faltar associação ao ativo; nesse caso, desfaz somente
         *         os efeitos de telemetria, preservando a resposta já registrada
         * @throws NullPointerException  se a lista for nula
         * @throws IllegalStateException se uma gravação JDBC falhar
         */
        public boolean saveTelemetry(List<Telemetry> readings) {
            return execute(() -> {
                // O bloqueio da reserva foi obtido antes do savepoint e continua válido
                // após o rollback parcial. Assim, outro worker não assume este pacote.
                var savepoint = connection.setSavepoint("processing_start");
                UUID messageId = messages.save(connection, packet.tenantId(), packet.deviceId(),
                        packet.id(), packet.payloadHash());
                // Conteúdo já registrado: messages.save devolve nulo e nenhuma posição
                // nova é gravada, mas a mensagem existente continua válida.
                for (int index = 0; messageId != null && index < readings.size(); index++) {
                    Telemetry telemetry = readings.get(index);
                    if (!telemetry.hasPosition()) continue;
                    var assetId = assignments.findAssetId(connection, packet.tenantId(),
                            packet.deviceId(), telemetry.recordedAt());
                    if (assetId.isEmpty()) {
                        // Sem associação, desfaz apenas o savepoint; a resposta já
                        // gravada antes dele permanece.
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

        /**
         * Marca a conclusão na mesma transação dos efeitos de telemetria.
         *
         * <p>Grava {@code processed}, incrementa {@code attempts} e preenche
         * {@code processed_at}; a confirmação depende do commit de
         * {@link #processNext}.</p>
         *
         * @throws IllegalStateException se a linha não estiver mais pendente ou se a
         *                               atualização JDBC falhar
         */
        public void markProcessed() {
            finish("processed", null);
        }

        /**
         * Encerra o pacote com falha e incrementa a tentativa.
         *
         * <p>Grava {@code rejected}, o código informado, {@code processed_at} e
         * incrementa {@code attempts}; não há nova tentativa automática.</p>
         *
         * @param errorCode motivo da rejeição
         * @throws IllegalStateException se a linha não estiver mais pendente ou se a
         *                               atualização JDBC falhar
         */
        public void reject(String errorCode) {
            finish("rejected", errorCode);
        }

        /**
         * Confere status e escopo mesmo com a linha já bloqueada pela reserva.
         *
         * <p>O {@code WHERE} repete tenant, dispositivo, id e {@code pending}; uma
         * linha ausente indica que o pacote deixou de estar pendente e vira
         * {@link SQLException}, convertida em {@link IllegalStateException} antes de
         * sair da camada.</p>
         *
         * @param status    novo estado do pacote
         * @param errorCode motivo gravado, ou nulo na conclusão
         * @throws IllegalStateException se a atualização não afetar exatamente uma
         *                               linha ou se o JDBC falhar
         */
        private void finish(String status, String errorCode) {
            execute(() -> {
                // Mesmo com o lock da reserva, o filtro de escopo e o estado pendente
                // impedem encerrar uma linha que não pertence a este processamento.
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
         * <p>Incrementa {@code attempts}, grava o código e adia {@code available_at}
         * usando o relógio do banco. O intervalo é truncado em segundos inteiros;
         * o status não muda, então o pacote volta a ser elegível quando o prazo
         * vencer.</p>
         *
         * @param errorCode motivo da nova tentativa
         * @param delay intervalo até o pacote voltar a ser elegível
         * @throws NullPointerException  se o intervalo for nulo
         * @throws IllegalStateException se a linha não estiver mais pendente ou se o
         *                               JDBC falhar
         */
        public void scheduleRetry(String errorCode, Duration delay) {
            execute(() -> {
                // O prazo é calculado no banco e o atraso é truncado para segundos,
                // pois o intervalo SQL é montado como número inteiro.
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

        /**
         * Liga os três parâmetros de escopo na ordem tenant, dispositivo e pacote.
         *
         * @param statement instrução que receberá os parâmetros
         * @param start     índice do primeiro parâmetro de escopo
         * @throws SQLException se a ligação dos parâmetros falhar
         */
        private void bindScope(java.sql.PreparedStatement statement, int start) throws SQLException {
            statement.setObject(start, packet.tenantId());
            statement.setObject(start + 1, packet.deviceId());
            statement.setObject(start + 2, packet.id());
        }

        /**
         * Exige que a atualização tenha alcançado exatamente o pacote reservado.
         *
         * @param updated número de linhas afetadas pela atualização
         * @throws SQLException se nenhuma linha ou mais de uma tiver sido afetada
         */
        private void requireUpdated(int updated) throws SQLException {
            if (updated != 1) throw new SQLException("Pending packet not found: " + packet.id());
        }
    }

    /**
     * Mantém detalhes JDBC e a causa SQL na camada de persistência.
     *
     * @param operation operação interna que pode lançar {@link SQLException}
     * @param <T>       tipo do resultado da operação
     * @return resultado produzido pela operação
     * @throws IllegalStateException se a operação falhar no driver
     */
    private static <T> T execute(SqlOperation<T> operation) {
        try {
            return operation.run();
        } catch (SQLException error) {
            throw databaseFailure(error);
        }
    }

    /**
     * Converte a falha de JDBC em exceção da camada, preservando a causa original.
     *
     * @param error falha capturada do driver
     * @return exceção de estado a ser propagada ao worker
     */
    private static IllegalStateException databaseFailure(SQLException error) {
        return new IllegalStateException("Worker database operation failed", error);
    }

    /** Operação interna que pode falhar no driver; não faz parte do contrato do worker. */
    @FunctionalInterface
    private interface SqlOperation<T> {
        /**
         * Executa a operação JDBC.
         *
         * @return resultado da operação
         * @throws SQLException se o driver ou a instrução falhar
         */
        T run() throws SQLException;
    }
}
