package software.aporia.tracking.worker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import software.aporia.tracking.observability.metrics.WorkerMetrics;
import software.aporia.tracking.protocol.DecoderRegistry;
import software.aporia.tracking.protocol.suntech.SuntechDecoder;
import software.aporia.tracking.protocol.teltonika.TeltonikaAvl;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

/** Executa consumidores concorrentes e verifica que cada pacote produz os efeitos esperados uma única vez. */
class TrackingWorkerConcurrencyIT {

    private SimpleMeterRegistry meterRegistry;
    private WorkerMetrics metrics;
    private DecoderRegistry decoderRegistry;

    @BeforeEach
    void setUpWorkerDependencies() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new WorkerMetrics(meterRegistry);
        decoderRegistry = new DecoderRegistry(List.of(
                new SuntechDecoder(), new TeltonikaAvl()));
    }

    @AfterEach
    void closeMeterRegistry() {
        meterRegistry.close();
    }

    /**
     * Executa dois consumidores e verifica que cada pacote produz seus efeitos uma única vez.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldProcessTwoPacketsConcurrentlyWithoutDuplication()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "1234567890"
        )) {

            // Dois pacotes distintos do mesmo dispositivo.
            insertPacket(
                    dataSource,
                    fixtures,
                    "ALV;1234567890\r"
            );

            insertPacket(
                    dataSource,
                    fixtures,
                    "STT;1234567890;013831;20261120;08:45:00;"
                    + "-16.6700;-49.2500;45.5;1\r"
            );

            TrackingWorker worker1 = new TrackingWorker(
                    dataSource, fixtures.tenantId(), decoderRegistry, metrics);

            TrackingWorker worker2 = new TrackingWorker(
                    dataSource, fixtures.tenantId(), decoderRegistry, metrics);

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(2);

            AtomicInteger processed = new AtomicInteger();
            AtomicReference<Exception> failure = new AtomicReference<>();

            Runnable task1 = () -> {
                try {
                    start.await();

                    if (worker1.processOne()) {
                        processed.incrementAndGet();
                    }

                } catch (InterruptedException | RuntimeException e) {
                    failure.compareAndSet(null, e);
                } finally {
                    finished.countDown();
                }
            };

            Runnable task2 = () -> {
                try {
                    start.await();

                    if (worker2.processOne()) {
                        processed.incrementAndGet();
                    }

                } catch (InterruptedException | RuntimeException e) {
                    failure.compareAndSet(null, e);
                } finally {
                    finished.countDown();
                }
            };

            Thread.ofVirtual().start(task1);
            Thread.ofVirtual().start(task2);

            start.countDown();

            assertTrue(
                    finished.await(10, TimeUnit.SECONDS),
                    "Workers did not finish in time"
            );

            if (failure.get() != null) {
                throw failure.get();
            }
            assertEquals(2, processed.get());

            try (Connection connection
                    = dataSource.getConnection()) {

                // Ambos os pacotes devem estar processados.
                assertEquals(
                        2,
                        count(connection, """
                        SELECT COUNT(*)
                        FROM raw_packets
                        WHERE tenant_id = ?
                          AND status = 'processed'
                          AND attempts = 1
                        """, fixtures.tenantId())
                );

                // Não deve haver mensagens duplicadas.
                assertEquals(
                        2,
                        count(connection, """
                        SELECT COUNT(*)
                        FROM messages
                        WHERE tenant_id = ?
                        """, fixtures.tenantId())
                );

                // Somente o pacote STT gera posição.
                assertEquals(
                        1,
                        count(connection, """
                        SELECT COUNT(*)
                        FROM positions
                        WHERE tenant_id = ?
                        """, fixtures.tenantId())
                );

                assertEquals(
                        1,
                        count(connection, """
                        SELECT COUNT(*)
                        FROM outbox_events
                        WHERE tenant_id = ?
                        """, fixtures.tenantId())
                );
            }
        }
    }

    /**
     * Insere na fila o pacote bruto a ser disputado pelos consumidores.
     *
     * @param dataSource fonte de conexões JDBC para a operação
     * @param fixtures cadastro de teste utilizado no cenário
     * @param frame quadro recebido para interpretação ou ingestão
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private void insertPacket(
            javax.sql.DataSource dataSource,
            TestFixtures fixtures,
            String frame
    ) throws Exception {

        UUID packetId = UUID.randomUUID();

        byte[] payload = frame.getBytes(
                StandardCharsets.US_ASCII
        );

        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(payload)
        );

        try (Connection connection = dataSource.getConnection(); PreparedStatement statement
                = connection.prepareStatement("""
                     INSERT INTO raw_packets (
                         id, tenant_id, device_id,
                         payload, payload_hash,
                         decoder_version, correlation_id
                     )
                     VALUES (?, ?, ?, ?, ?,
                             'suntech-ascii-v3', ?)
                     """)) {

            statement.setObject(1, packetId);
            statement.setObject(2, fixtures.tenantId());
            statement.setObject(3, fixtures.deviceId());
            statement.setBytes(4, payload);
            statement.setString(5, hash);
            statement.setObject(6, UUID.randomUUID());

            statement.executeUpdate();
        }
    }

    /**
     * Conta as linhas do tenant para detectar ausência ou duplicação de efeitos.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param sql instrução SQL executada na conexão de teste
     * @param tenantId identificador do tenant que delimita a operação
     * @return quantidade de registros no escopo da consulta
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private long count(
            Connection connection,
            String sql,
            UUID tenantId
    ) throws Exception {

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setObject(1, tenantId);

            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}

