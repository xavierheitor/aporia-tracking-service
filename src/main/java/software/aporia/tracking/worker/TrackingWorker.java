package software.aporia.tracking.worker;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.aporia.tracking.observability.metrics.WorkerMetrics;
import software.aporia.tracking.protocol.DecodedPacket;
import software.aporia.tracking.protocol.DecoderRegistry;
import software.aporia.tracking.storage.packet.PacketProcessingRepository;
import software.aporia.tracking.storage.packet.PacketProcessingRepository.Packet;
import software.aporia.tracking.storage.packet.PacketProcessingRepository.Processing;

/**
 * Orquestra a validação e a decodificação dos pacotes e decide seus desfechos.
 *
 * <ol>
 * <li>{@link #processOne()} solicita ao repositório uma recepção elegível.</li>
 * <li>{@code decode()} confere hash, protocolo e identidade antes de aceitar o conteúdo.</li>
 * <li>{@code processPacket()} solicita a persistência de resposta e telemetria. Se faltar associação ao ativo, aplica a
 * política de retentativa.</li>
 * <li>O repositório confirma a transação antes de devolver o resultado. Somente então o worker registra o desfecho nos
 * logs e informa que tratou um pacote.</li>
 * </ol>
 *
 * <p>
 * SQL, conexão, bloqueios e rollback pertencem a {@link PacketProcessingRepository}. A resposta de comando é preservada
 * no rollback parcial da telemetria; uma falha de persistência, por sua vez, desfaz a transação inteira e chega ao
 * chamador.
 * </p>
 */
public class TrackingWorker {
    private static final Logger log = LoggerFactory.getLogger(TrackingWorker.class);

    /** Seleciona a interpretação pela versão gravada com os bytes brutos. */
    private final DecoderRegistry decoderRegistry;
    /** Mantém reserva e efeitos do processamento na mesma transação. */
    private final PacketProcessingRepository processingRepository;
    /** Restringe a seleção a um tenant; nulo habilita o consumidor global. */
    private final UUID tenantFilter;
    private final WorkerMetrics metrics;

    public TrackingWorker(
            DataSource dataSource,
            UUID tenantFilter,
            DecoderRegistry decoderRegistry,
            WorkerMetrics metrics) {

        this.processingRepository = new PacketProcessingRepository(dataSource);

        this.tenantFilter = tenantFilter;

        this.decoderRegistry = Objects.requireNonNull(decoderRegistry);

        this.metrics = Objects.requireNonNull(metrics);
    }

    /**
     * Solicita o processamento de no máximo um pacote elegível e publica o desfecho somente depois que o repositório
     * confirmar a transação.
     *
     * <p>
     * Uma rejeição ou retentativa persistida também conta como pacote tratado. Uma falha de banco é propagada, sem
     * produzir log de conclusão.
     * </p>
     *
     * @return verdadeiro se um pacote foi processado, rejeitado ou reagendado; falso quando não houver pacote elegível
     * @throws IllegalStateException se a transação de persistência falhar
     */
    public boolean processOne() {

        long startedAt = System.nanoTime();

        ProcessingResult result;

        try {
            result = processingRepository.processNext(
                    tenantFilter,
                    decoderRegistry.versions(),
                    this::processPacket)
                    .orElseGet(ProcessingResult::empty);

        } catch (RuntimeException error) {
            metrics.cycleFailed();
            throw error;
        }

        switch (result.status()) {
            case EMPTY -> metrics.cycleEmpty();
            case PROCESSED -> metrics.packetProcessed();
            case REJECTED -> metrics.packetRejected();
            case RETRY_SCHEDULED -> metrics.retryScheduled();
        }

        if (result.status() != ProcessingResult.Status.EMPTY) {
            metrics.recordProcessingDuration(
                    System.nanoTime() - startedAt);
        }

        logResult(result);

        return result.status() != ProcessingResult.Status.EMPTY;
    }

    /**
     * Valida o pacote reservado, solicita a gravação da resposta e da telemetria e decide entre conclusão, rejeição ou
     * nova tentativa.
     *
     * <p>
     * O repositório mantém a transação aberta durante esta chamada. O resultado descreve as alterações solicitadas, que
     * só ficam confirmadas após o retorno ao repositório e o commit.
     * </p>
     *
     * @param processing contexto de persistência restrito ao pacote reservado
     * @return desfecho a publicar somente depois da confirmação da transação
     * @throws IllegalStateException se uma operação de persistência falhar
     */
    private ProcessingResult processPacket(Processing processing) {
        Packet packet = processing.packet();
        DecodedPacket decoded;
        try {
            decoded = decode(packet);
        } catch (InvalidPacketException error) {
            processing.reject(error.getErrorCode());
            return ProcessingResult.rejected(packet.id(), error.getErrorCode(), packet.attempts() + 1);
        }

        // A resposta pertence à recepção mesmo quando uma posição aguarda associação.
        if (decoded.responseType() != null) {
            processing.saveResponse(decoded.responseType());
        }
        if (!processing.saveTelemetry(decoded.readings())) {
            return scheduleRetry(processing, "ASSIGNMENT_NOT_FOUND");
        }
        processing.markProcessed();
        return ProcessingResult.processed(packet.id());
    }

    /**
     * Escolhe o decoder pela versão persistida e valida hash, protocolo e identidade. Uma falha de formato reconhecida
     * pelo decoder vira rejeição permanente; outras falhas inesperadas continuam até o chamador para provocar rollback.
     *
     * @param packet dados da recepção e do cadastro obtidos na reserva
     * @return conteúdo interpretado, incluindo leituras e eventual tipo de resposta
     * @throws InvalidPacketException   se o hash, o protocolo, o formato ou a identidade forem incompatíveis com o
     *                                  pacote e o cadastro
     * @throws IllegalArgumentException se a versão não tiver decoder registrado
     * @throws IllegalStateException    se SHA-256 não estiver disponível na JVM
     */
    private DecodedPacket decode(Packet packet) {
        var decoder = decoderRegistry.get(packet.decoderVersion());
        byte[] payload = packet.payload();
        validateHash(payload, packet.payloadHash());
        if (!packet.protocol().equals(decoder.protocolId())) {
            throw new InvalidPacketException("DEVICE_PROTOCOL_MISMATCH");
        }
        DecodedPacket decoded;
        try {
            decoded = decoder.decodePacket(payload, packet.registeredIdentity());
        } catch (IllegalArgumentException error) {
            throw new InvalidPacketException("INVALID_PROTOCOL_FRAME", error);
        }
        if (!packet.registeredIdentity().equals(decoded.identity())) {
            throw new InvalidPacketException("DEVICE_IDENTITY_MISMATCH");
        }
        return decoded;
    }

    /**
     * Aplica a política de retentativa usando a quantidade de tentativas da reserva. Antes do limite, solicita novo
     * prazo; ao atingir o limite, solicita rejeição.
     *
     * @param processing contexto do pacote cujos efeitos de telemetria foram desfeitos
     * @param errorCode  motivo que deve acompanhar a nova tentativa ou rejeição
     * @return desfecho com a quantidade de tentativas e o atraso, quando aplicável
     * @throws IllegalStateException se a gravação do desfecho falhar
     */
    private ProcessingResult scheduleRetry(Processing processing, String errorCode) {
        Packet packet = processing.packet();
        int attempts = packet.attempts() + 1;
        if (attempts >= RetryPolicy.MAX_ATTEMPTS) {
            processing.reject(errorCode);
            return ProcessingResult.rejected(packet.id(), errorCode, attempts);
        }
        var delay = RetryPolicy.delayForAttempt(attempts);
        processing.scheduleRetry(errorCode, delay);
        return ProcessingResult.retryScheduled(packet.id(), errorCode, attempts, delay.toSeconds());
    }

    /**
     * Verifica se o conteúdo corresponde ao hash SHA-256 armazenado na ingestão.
     *
     * @param payload    bytes do conteúdo da mensagem
     * @param storedHash resumo SHA-256 hexadecimal salvo na ingestão
     * @throws InvalidPacketException se os bytes não corresponderem ao hash armazenado
     * @throws IllegalStateException  se SHA-256 não estiver disponível na JVM
     */
    private void validateHash(
            byte[] payload,
            String storedHash) {

        final byte[] calculatedHash;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            calculatedHash = digest.digest(payload);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 unavailable",
                    e);
        }

        String calculatedHex = HexFormat.of()
                .formatHex(calculatedHash);

        if (!calculatedHex.equals(storedHash)) {
            throw new InvalidPacketException(
                    "HASH_MISMATCH");
        }
    }

    /**
     * Registra o desfecho já confirmado no banco, sem expor os bytes do pacote. Conclusões usam INFO; rejeições e
     * retentativas usam WARN. Uma fila sem pacote elegível não gera log a cada consulta.
     *
     * @param result resultado devolvido pelo repositório após o commit
     */
    private void logResult(ProcessingResult result) {
        switch (result.status()) {
            case EMPTY -> {
                // Fila vazia: não gerar logs.
            }
            case PROCESSED ->
                log.info(
                        "Packet processed: packetId={}",
                        result.packetId());
            case REJECTED ->
                log.warn(
                        "Packet rejected: packetId={}, errorCode={}, attempts={}",
                        result.packetId(),
                        result.errorCode(),
                        result.attempts());
            case RETRY_SCHEDULED ->
                log.warn(
                        "Packet retry scheduled: packetId={}, errorCode={}, "
                                + "attempts={}, delaySeconds={}",
                        result.packetId(),
                        result.errorCode(),
                        result.attempts(),
                        result.retryDelaySeconds());
        }
    }

}
