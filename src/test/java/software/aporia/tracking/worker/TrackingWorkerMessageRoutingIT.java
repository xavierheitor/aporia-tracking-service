package software.aporia.tracking.worker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import software.aporia.tracking.observability.metrics.WorkerMetrics;
import software.aporia.tracking.protocol.DecoderRegistry;
import software.aporia.tracking.protocol.suntech.SuntechDecoder;
import software.aporia.tracking.protocol.teltonika.TeltonikaAvl;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

/** Verifica a persistência de respostas Suntech e o tratamento de telemetria presente na mesma mensagem. */
class TrackingWorkerMessageRoutingIT {

    /** Registro isolado para observar os contadores de cada cenário. */
    private SimpleMeterRegistry meterRegistry;
    /** Métricas do worker ligadas ao registro acima. */
    private WorkerMetrics metrics;
    /** Decodificadores Suntech e Teltonika disponíveis ao worker. */
    private DecoderRegistry decoderRegistry;

    /** Prepara métricas isoladas e os dois decodificadores usados pelo worker. */
    @BeforeEach
    void setUpWorkerDependencies() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new WorkerMetrics(meterRegistry);
        decoderRegistry = new DecoderRegistry(List.of(
                new SuntechDecoder(), new TeltonikaAvl()));
    }

    /** Fecha o registro de métricas para não vazar estado entre testes. */
    @AfterEach
    void closeMeterRegistry() {
        meterRegistry.close();
    }

    /** Identidade do equipamento usada no cenário. */
    private static final String IDENTITY = "1234567890";

    /**
     * Codifica a mensagem de exemplo com o charset usado no protocolo.
     *
     * @param value valor a interpretar
     * @return bytes ASCII da mensagem de exemplo
     */
    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Persiste o quadro de entrada usado no cenário de roteamento.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param fixtures cadastro de teste utilizado no cenário
     * @param payload bytes do conteúdo da mensagem
     * @return UUID atribuído ao pacote de teste
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private UUID insertPacket(
            Connection connection,
            TestFixtures fixtures,
            byte[] payload
    ) throws Exception {

        UUID packetId = UUID.randomUUID();

        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(payload)
        );

        try (PreparedStatement statement
                = connection.prepareStatement("""
                    INSERT INTO raw_packets (
                        id,
                        tenant_id,
                        device_id,
                        payload,
                        payload_hash,
                        decoder_version,
                        correlation_id
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

        return packetId;
    }

    /**
     * Confere no banco a conclusão do processamento do pacote.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param packetId identificador da recepção bruta
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private void assertPacketProcessed(
            Connection connection,
            UUID packetId
    ) throws Exception {

        try (PreparedStatement statement
                = connection.prepareStatement("""
                    SELECT status
                    FROM raw_packets
                    WHERE id = ?
                    """)) {

            statement.setObject(1, packetId);

            try (ResultSet result = statement.executeQuery()) {

                assertTrue(result.next());
                assertEquals(
                        "processed",
                        result.getString("status")
                );
            }
        }
    }

    /**
     * Compara a resposta persistida com o tipo e os bytes esperados.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param packetId identificador da recepção bruta
     * @param expectedType tipo da resposta de comando
     * @param expectedPayload bytes esperados na resposta persistida
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private void assertResponse(
            Connection connection,
            UUID packetId,
            String expectedType,
            byte[] expectedPayload
    ) throws Exception {

        try (PreparedStatement statement
                = connection.prepareStatement("""
                    SELECT
                        response_type,
                        result,
                        command_id,
                        payload
                    FROM device_command_responses
                    WHERE raw_packet_id = ?
                    """)) {

            statement.setObject(1, packetId);

            try (ResultSet result = statement.executeQuery()) {

                assertTrue(result.next());

                assertEquals(
                        expectedType,
                        result.getString("response_type")
                );

                assertEquals(
                        "unknown",
                        result.getString("result")
                );

                assertNull(
                        result.getObject("command_id")
                );

                assertArrayEquals(
                        expectedPayload,
                        result.getBytes("payload")
                );

                assertFalse(result.next());
            }
        }
    }

    /**
     * Conta as posições do cenário para distinguir resposta simples de composta.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param packetId identificador da recepção bruta
     * @return quantidade de registros no escopo da consulta
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    private int countPositions(
            Connection connection,
            UUID packetId
    ) throws Exception {

        try (PreparedStatement statement
                = connection.prepareStatement("""
                    SELECT COUNT(*)
                    FROM positions p
                    JOIN messages m
                      ON m.id = p.message_id
                    WHERE m.raw_packet_id = ?
                    """)) {

            statement.setObject(1, packetId);

            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    /**
     * Persiste uma resposta RES sem exigir que ela contenha posição.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldProcessConventionalCommandResponse()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, IDENTITY
        )) {

            byte[] payload = bytes(
                    "RES;1234567890;03;04;01;"
                    + "STB.201.1.1.11\r"
            );

            UUID packetId;

            try (Connection connection
                    = dataSource.getConnection()) {

                packetId = insertPacket(
                        connection, fixtures, payload
                );
            }

            TrackingWorker worker = new TrackingWorker(
                    dataSource, fixtures.tenantId(), decoderRegistry, metrics);

            assertTrue(worker.processOne());

            try (Connection connection
                    = dataSource.getConnection()) {

                assertPacketProcessed(connection, packetId);

                assertResponse(
                        connection,
                        packetId,
                        "RES",
                        payload
                );

                assertEquals(
                        0,
                        countPositions(connection, packetId)
                );
            }
        }
    }

    /**
     * Persiste o retorno de programação RPR recebido do equipamento.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldProcessRprResponse()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, IDENTITY
        )) {

            byte[] payload = bytes(
                    "RPR;1234567890;OK;10;13#0\r"
            );

            UUID packetId;

            try (Connection connection
                    = dataSource.getConnection()) {

                packetId = insertPacket(
                        connection, fixtures, payload
                );
            }

            TrackingWorker worker = new TrackingWorker(
                    dataSource, fixtures.tenantId(), decoderRegistry, metrics);

            assertTrue(worker.processOne());

            try (Connection connection
                    = dataSource.getConnection()) {

                assertPacketProcessed(connection, packetId);

                assertResponse(
                        connection,
                        packetId,
                        "RPR",
                        payload
                );

                assertEquals(
                        0,
                        countPositions(connection, packetId)
                );
            }
        }
    }

    /**
     * Uma versão desconhecida aguarda outro worker sem impedir a leitura da próxima recepção.
     *
     * @throws Exception se a preparação ou a verificação falhar
     */
    @Test
    void skipsUnknownVersionAndKeepsPacketPending() throws Exception {
        try (var db = TestDatabase.createDataSource(); var device = new TestFixtures(db, IDENTITY);
             var c = db.getConnection()) {
            var worker = new TrackingWorker(db, device.tenantId(), decoderRegistry, metrics);
            var alive = bytes("ALV;" + IDENTITY + "\r");
            UUID older = insertPacket(c, device, alive);
            try (var update = c.prepareStatement("UPDATE raw_packets SET decoder_version='future', "
                    + "received_at=now()-interval '1 minute' WHERE id=?")) {
                update.setObject(1, older);
                update.executeUpdate();
            }
            UUID newer = insertPacket(c, device, alive);
            assertTrue(worker.processOne());
            assertPacketProcessed(c, newer);
            assertFalse(worker.processOne());
            try (var query = c.prepareStatement("SELECT status,attempts FROM raw_packets WHERE id=?")) {
                query.setObject(1, older);
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("pending", row.getString(1));
                    assertEquals(0, row.getInt(2));
                }
            }
        }
    }

    /**
     * Uma posição sem ativo esgota as tentativas sem desfazer ou duplicar a resposta do pacote.
     *
     * @throws Exception se a preparação ou a verificação falhar
     */
    @Test
    void preservesResponseWhenEmbeddedPositionIsRejected() throws Exception {
        try (var db = TestDatabase.createDataSource(); var device = new TestFixtures(db, IDENTITY);
             var c = db.getConnection()) {
            var payload = bytes("RES;STT;" + IDENTITY + ";013831;20261120;08:45:00;-16.67;-49.25;45.5;1\r");
            UUID id = insertPacket(c, device, payload);
            try (var remove = c.prepareStatement("DELETE FROM assignments WHERE tenant_id=?")) {
                remove.setObject(1, device.tenantId());
                remove.executeUpdate();
            }
            var worker = new TrackingWorker(db, device.tenantId(), decoderRegistry, metrics);
            try (var due = c.prepareStatement("UPDATE raw_packets SET available_at=now() WHERE id=?")) {
                due.setObject(1, id);
                for (int attempt = 0; attempt < RetryPolicy.MAX_ATTEMPTS; attempt++) {
                    due.executeUpdate();
                    assertTrue(worker.processOne());
                    assertResponse(c, id, "RES", payload);
                }
            }
            assertEquals(0, countPositions(c, id));
            try (var query = c.prepareStatement("SELECT status FROM raw_packets WHERE id=?")) {
                query.setObject(1, id);
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("rejected", row.getString(1));
                }
            }
        }
    }

    /**
     * Confere a posição e a resposta produzidas pelo mesmo quadro composto.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldProcessTelemetryInsideCommandResponse()
            throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, IDENTITY
        )) {

            byte[] payload = bytes(
                    "RES;STT;1234567890;013831;"
                    + "20261120;08:45:00;"
                    + "-16.6700;-49.2500;45.5;1\r"
            );

            UUID packetId;

            try (Connection connection
                    = dataSource.getConnection()) {

                packetId = insertPacket(
                        connection, fixtures, payload
                );
            }

            TrackingWorker worker = new TrackingWorker(
                    dataSource, fixtures.tenantId(), decoderRegistry, metrics);

            assertTrue(worker.processOne());

            try (Connection connection
                    = dataSource.getConnection()) {

                assertPacketProcessed(connection, packetId);

                assertResponse(
                        connection,
                        packetId,
                        "RES",
                        payload
                );

                assertEquals(
                        1,
                        countPositions(connection, packetId)
                );

                try (PreparedStatement statement
                        = connection.prepareStatement("""
                            SELECT
                                ST_Y(p.geom) AS latitude,
                                ST_X(p.geom) AS longitude,
                                p.speed_kph
                            FROM positions p
                            JOIN messages m
                              ON m.id = p.message_id
                            WHERE m.raw_packet_id = ?
                            """)) {

                    statement.setObject(1, packetId);

                    try (ResultSet result
                            = statement.executeQuery()) {

                        assertTrue(result.next());

                        assertEquals(
                                -16.67,
                                result.getDouble("latitude"),
                                0.000001
                        );

                        assertEquals(
                                -49.25,
                                result.getDouble("longitude"),
                                0.000001
                        );

                        assertEquals(
                                45.5,
                                result.getDouble("speed_kph"),
                                0.000001
                        );
                    }
                }
            }
        }
    }
}
