package software.aporia.tracking.storage.command;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import software.aporia.tracking.support.TestDatabase;
import software.aporia.tracking.support.TestFixtures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercita enfileiramento, validação do dispositivo e isolamento das solicitações entre tenants. */
class DeviceCommandRepositoryIT {

    /** Repositório utilizado para as operações sobre comandos. */
    private final DeviceCommandRepository repository
            = new DeviceCommandRepository();

    /**
     * Monta os bytes da solicitação para o equipamento usado no teste.
     *
     * @param identity identidade declarada pelo rastreador no protocolo
     * @return bytes ASCII da mensagem de exemplo
     */
    private byte[] command(String identity) {
        return ("CMD;" + identity + ";03;04;1\r").getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Confere o enfileiramento de um comando para um cadastro habilitado.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldEnqueueCommandForEnabledDevice() throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500001"
        ); Connection connection = dataSource.getConnection()) {

            byte[] payload = command(fixtures.deviceIdentity());

            UUID commandId = repository.enqueue(
                    connection,
                    fixtures.tenantId(),
                    fixtures.deviceId(),
                    "suntech",
                    payload,
                    Instant.now().plusSeconds(300)
            );

            assertEquals(
                    "queued",
                    repository.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );

            try (PreparedStatement statement
                    = connection.prepareStatement("""
                        SELECT protocol, payload, attempts, status
                        FROM device_commands
                        WHERE id = ?
                        """)) {

                statement.setObject(1, commandId);

                try (ResultSet result = statement.executeQuery()) {

                    assertTrue(result.next());
                    assertEquals(
                            "suntech",
                            result.getString("protocol")
                    );
                    assertArrayEquals(
                            payload,
                            result.getBytes("payload")
                    );
                    assertEquals(0, result.getInt("attempts"));
                    assertEquals(
                            "queued",
                            result.getString("status")
                    );
                    assertFalse(result.next());
                }
            }
        }
    }

    /**
     * Rejeita o enfileiramento para cadastro desabilitado.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectDisabledDevice() throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500002"
        ); Connection connection = dataSource.getConnection()) {

            try (PreparedStatement statement
                    = connection.prepareStatement("""
                        UPDATE devices
                        SET enabled = false
                        WHERE id = ?
                        """)) {

                statement.setObject(1, fixtures.deviceId());
                statement.executeUpdate();
            }

            var error = assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.enqueue(
                            connection,
                            fixtures.tenantId(),
                            fixtures.deviceId(),
                            "suntech",
                            command(fixtures.deviceIdentity()),
                            Instant.now().plusSeconds(300)
                    )
            );
            assertEquals("UNKNOWN_OR_DISABLED_DEVICE", error.getMessage());
        }
    }

    /**
     * Impede que um tenant enfileire comandos para equipamento de outro.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectCrossTenantCommand() throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500003"
        ); Connection connection = dataSource.getConnection()) {

            var error = assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.enqueue(
                            connection,
                            UUID.randomUUID(),
                            fixtures.deviceId(),
                            "suntech",
                            command(fixtures.deviceIdentity()),
                            Instant.now().plusSeconds(300)
                    )
            );
            assertEquals("UNKNOWN_OR_DISABLED_DEVICE", error.getMessage());
        }
    }

    /**
     * Rejeita um comando que já ultrapassou o prazo ao entrar na fila.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldRejectExpiredCommand() throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500004"
        ); Connection connection = dataSource.getConnection()) {

            var error = assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.enqueue(
                            connection,
                            fixtures.tenantId(),
                            fixtures.deviceId(),
                            "suntech",
                            command(fixtures.deviceIdentity()),
                            Instant.now().minusSeconds(60)
                    )
            );
            assertEquals("INVALID_EXPIRATION", error.getMessage());
        }
    }

    /**
     * Confere o isolamento por tenant na consulta do estado.
     *
     * @throws Exception se a preparação, execução ou verificação do cenário falhar
     */
    @Test
    void shouldNotExposeCommandAcrossTenants() throws Exception {

        try (
                var dataSource = TestDatabase.createDataSource(); var fixtures = new TestFixtures(
                dataSource, "9876500005"
        ); Connection connection = dataSource.getConnection()) {

            UUID commandId = repository.enqueue(
                    connection,
                    fixtures.tenantId(),
                    fixtures.deviceId(),
                    "suntech",
                    command(fixtures.deviceIdentity()),
                    Instant.now().plusSeconds(300)
            );

            assertTrue(
                    repository.findStatus(
                            connection,
                            UUID.randomUUID(),
                            commandId
                    ).isEmpty()
            );

            assertEquals(
                    "queued",
                    repository.findStatus(
                            connection,
                            fixtures.tenantId(),
                            commandId
                    ).orElseThrow()
            );
        }
    }
}
