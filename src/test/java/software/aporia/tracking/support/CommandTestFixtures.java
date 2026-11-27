package software.aporia.tracking.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import software.aporia.tracking.storage.command.DeviceCommandDispatchRepository;

/**
 * Monta comandos históricos ou inválidos sem expor atalhos na persistência de produção.
 *
 * <p>As rotinas gravam SQL direto nas tabelas para preparar estados que a API de produção não
 * permite criar, como um comando sem auditoria ou um comando já expirado. A fixture não chama
 * commit nem rollback: a confirmação depende do modo da conexão e do chamador, e a limpeza cabe a
 * {@link TestFixtures#close()} ou ao rollback do cenário. Nenhum método desta classe é usado por
 * código de produção.</p>
 */
public final class CommandTestFixtures {
    /** Classe utilitária: não deve ser instanciada. */
    private CommandTestFixtures() {}

    /**
     * Insere um comando em {@code queued} sem {@code requested_by} nem {@code request_key}.
     *
     * <p>Cenário: os testes precisam de um comando que a reserva aceite, mas cuja transmissão a
     * autorização rejeite por falta de auditoria, ou de um comando cujo estado e prazo o teste
     * ajusta depois. Ação: executa um INSERT direto com os bytes e o prazo informados. Resultado:
     * devolve o UUID gerado; o commit fica com o chamador.</p>
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param tenant tenant proprietário do comando
     * @param device equipamento de destino
     * @param protocol protocolo de saída
     * @param payload bytes do comando
     * @param expiresAt instante limite do comando
     * @return UUID do comando inserido
     * @throws SQLException se a operação JDBC falhar
     */
    public static UUID enqueue(Connection connection, UUID tenant, UUID device, String protocol,
                               byte[] payload, Instant expiresAt) throws SQLException {
        UUID id = UUID.randomUUID();
        // Sem requested_by/request_key o comando não satisfaz a autorização de transmissão.
        try (var query = connection.prepareStatement("""
                INSERT INTO device_commands(id,tenant_id,device_id,protocol,payload,expires_at)
                VALUES (?,?,?,?,?,?)
                """)) {
            query.setObject(1, id);
            query.setObject(2, tenant);
            query.setObject(3, device);
            query.setString(4, protocol);
            query.setBytes(5, payload);
            // TIMESTAMPTZ usa OffsetDateTime UTC no driver PostgreSQL.
            query.setObject(6, expiresAt.atOffset(ZoneOffset.UTC));
            query.executeUpdate();
        }
        return id;
    }

    /**
     * Chama a reserva real com o tenant, o dispositivo e o token de sessão publicados pela fixture.
     *
     * <p>Resultado: a reserva elegível marcada como {@code sending}, ou vazio se não houver comando
     * que a sessão possa assumir. A conexão precisa estar com autocommit desligado; a confirmação
     * pertence ao teste.</p>
     *
     * @param repository repositório de reserva exercitado pelo cenário
     * @param connection conexão com transação aberta
     * @param fixture cadastro de teste que define o escopo da sessão
     * @return reserva do comando elegível, se houver
     * @throws SQLException se a operação JDBC falhar
     */
    public static Optional<DeviceCommandDispatchRepository.ClaimedCommand> claim(
            DeviceCommandDispatchRepository repository, Connection connection, TestFixtures fixture) throws SQLException {
        return repository.claimForSession(connection, fixture.tenantId(), fixture.deviceId(), fixture.sessionToken());
    }
    /**
     * Lê o status persistido no escopo do tenant para as asserções, sem criar API de leitura usada
     * só por testes.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param tenant tenant proprietário do comando
     * @param id identificador do comando consultado
     * @return status persistido, ou vazio se o comando não existir nesse tenant
     * @throws SQLException se a operação JDBC falhar
     */
    public static Optional<String> findStatus(Connection connection, UUID tenant, UUID id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT status FROM device_commands WHERE tenant_id=? AND id=?")) {
            query.setObject(1, tenant);
            query.setObject(2, id);
            try (var row = query.executeQuery()) {
                return row.next() ? Optional.of(row.getString(1)) : Optional.empty();
            }
        }
    }

}
