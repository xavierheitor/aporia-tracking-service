package software.aporia.tracking.storage.packet;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import software.aporia.tracking.core.ReceivedFrame;
import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica a ingestão para um equipamento cadastrado e o tratamento de identidades desconhecidas.
 *
 * <p>A ingestão revalida o dispositivo habilitado e grava o pacote bruto na mesma transação. Os
 * cenários usam um tenant próprio de {@link TestFixtures}, removido ao final, e conferem o estado
 * persistido no escopo do tenant.</p>
 */
class JdbcPacketIngestionIT {

    /**
     * Cenário: dispositivo Suntech habilitado, com identidade fixa. Ação: ingere um quadro ALV da
     * sessão. Resultado: a recepção é gravada com os mesmos bytes, status {@code pending} e o
     * dispositivo do cadastro.
     *
     * @throws SQLException se a operação JDBC falhar
     */
    @Test
    void shouldAcceptRegisteredDevice() throws SQLException {
        try (var dataSource = TestDatabase.createDataSource();
             var fixtures = new TestFixtures(dataSource, "9876500061")) {
            String identity = fixtures.deviceIdentity();
            // A identidade e o escopo do quadro precisam coincidir com o cadastro e com a sessão.
            ReceivedFrame frame = new ReceivedFrame(
                    "suntech",
                    identity,
                    ("ALV;" + identity + "\r").getBytes(StandardCharsets.US_ASCII), "suntech-ascii-v3"
            );

            UUID packetId = new JdbcPacketIngestion(dataSource).accept(frame, fixtures.tenantId(), fixtures.deviceId());

            // A ingestão só devolve o UUID depois de confirmar a transação.
            assertNotNull(packetId);
            try (var connection = dataSource.getConnection(); var query = connection.prepareStatement("""
                    SELECT payload, status, device_id FROM raw_packets WHERE tenant_id=? AND id=?
                    """)) {
                query.setObject(1, fixtures.tenantId());
                query.setObject(2, packetId);
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertArrayEquals(frame.payload(), row.getBytes("payload"));
                    assertEquals("pending", row.getString("status"));
                    assertEquals(fixtures.deviceId(), row.getObject("device_id", UUID.class));
                }
            }
        }
    }

    /**
     * Cenário: identidade sem cadastro. Ação: ingere o quadro com tenant e dispositivo aleatórios.
     * Resultado: {@link IllegalArgumentException} com {@code Unknown or disabled device}; nenhum
     * pacote é gravado.
     */
    @Test
    void shouldRejectUnknownDevice() {

        ReceivedFrame frame = new ReceivedFrame(
                "suntech",
                "9999999999",
                "ALV;9999999999\r".getBytes(
                        StandardCharsets.US_ASCII
                ), "suntech-ascii-v3"
        );

        try (var dataSource = TestDatabase.createDataSource()) {
            var ingestion = new JdbcPacketIngestion(dataSource);
            // A consulta combina protocolo, identidade, tenant e dispositivo habilitado.
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> ingestion.accept(frame, UUID.randomUUID(), UUID.randomUUID())
            );

            assertEquals(
                    "Unknown or disabled device",
                    exception.getMessage()
            );
        }
    }

    /**
     * Cenário: dispositivo cadastrado da fixture. Ação: ingere com tenant ou dispositivo nulos e,
     * depois, com tenant ou dispositivo de outro escopo. Resultado: nulos falham com
     * {@link NullPointerException} antes do JDBC; escopos divergentes falham com
     * {@link IllegalArgumentException}; a contagem final é zero, confirmando que nenhuma tentativa
     * deixou pacote.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRequireMatchingSessionScope() throws Exception {
        try (var db = TestDatabase.createDataSource(); var fixture = new TestFixtures(db)) {
            var ingestion = new JdbcPacketIngestion(db);
            var frame = new ReceivedFrame("suntech", fixture.deviceIdentity(),
                    ("ALV;" + fixture.deviceIdentity() + "\r").getBytes(StandardCharsets.US_ASCII), "suntech-ascii-v3");
            // NullPointerException é lançada antes de abrir conexão, sem tocar no banco.
            assertThrows(NullPointerException.class, () -> ingestion.accept(frame, null, fixture.deviceId()));
            assertThrows(NullPointerException.class, () -> ingestion.accept(frame, fixture.tenantId(), null));
            assertThrows(IllegalArgumentException.class, () -> ingestion.accept(frame, UUID.randomUUID(), fixture.deviceId()));
            assertThrows(IllegalArgumentException.class, () -> ingestion.accept(frame, fixture.tenantId(), UUID.randomUUID()));
            // Cada aceite rejeitado abre a própria transação e não deixa pacote no tenant da fixture.
            try (var connection = db.getConnection(); var query = connection.prepareStatement(
                    "SELECT count(*) FROM raw_packets WHERE tenant_id=?")) {
                query.setObject(1, fixture.tenantId());
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals(0, row.getInt(1));
                }
            }
        }
    }

}
