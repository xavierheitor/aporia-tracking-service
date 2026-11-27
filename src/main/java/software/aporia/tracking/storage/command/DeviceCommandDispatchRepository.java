package software.aporia.tracking.storage.command;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reserva comandos e condiciona autorização e conclusão ao token da tentativa,
 * para impedir atualizações por um emissor obsoleto.
 *
 * <p>As operações usam a conexão do chamador e não confirmam nem desfazem a transação. O tenant delimita
 * todas as mutações; a reserva e o encerramento de falha também restringem o dispositivo. A reserva exige
 * transação aberta, bloqueia as linhas do comando e do dispositivo e grava um token novo, que as transições
 * seguintes precisam apresentar junto do estado esperado. O índice parcial {@code uq_commands_in_flight}
 * garante no máximo um comando em voo por dispositivo.</p>
 */
public final class DeviceCommandDispatchRepository {

    /**
     * Reserva identificada pelo token exigido nas transições seguintes. Os bytes são copiados na construção e
     * em cada leitura para preservar a tentativa mesmo que o chamador altere o array recebido.
     *
     * <p>{@code attempts} é o número da tentativa depois do incremento feito na reserva. O token não aparece
     * em {@link #toString()}.</p>
     *
     * @param id         UUID do comando reservado
     * @param tenantId   tenant da reserva
     * @param deviceId   equipamento de destino
     * @param protocol   protocolo de saída
     * @param payload    bytes reservados para a escrita
     * @param attempts   número da tentativa
     * @param claimToken token exigido nas transições condicionais
     */
    public record ClaimedCommand(
            UUID id,
            UUID tenantId,
            UUID deviceId,
            String protocol,
            byte[] payload,
            int attempts,
            UUID claimToken) {

        /**
         * Protege os bytes reservados contra alterações entre a reserva e a
         * autorização.
         *
         * @param id         identificador do registro consultado
         * @param tenantId   identificador do tenant que delimita a operação
         * @param deviceId   identificador do equipamento cadastrado
         * @param protocol   identificador do protocolo do equipamento
         * @param payload    bytes do conteúdo da mensagem
         * @param attempts   número de tentativas associado à operação
         * @param claimToken token que identifica a reserva vigente
         * @throws NullPointerException se algum componente obrigatório for nulo
         */
        public ClaimedCommand {
            Objects.requireNonNull(id);
            Objects.requireNonNull(tenantId);
            Objects.requireNonNull(deviceId);
            Objects.requireNonNull(protocol);
            Objects.requireNonNull(payload);
            Objects.requireNonNull(claimToken);

            payload = payload.clone();
        }

        /**
         * Entrega uma cópia dos bytes associados à tentativa.
         *
         * @return cópia dos bytes protegidos pela instância
         */
        @Override
        public byte[] payload() {
            return payload.clone();
        }

        /**
         * Resume a reserva para diagnóstico, expondo o tamanho do payload e omitindo
         * seu conteúdo e o token.
         *
         * @return resumo da reserva sem expor os bytes nem o token
         */
        @Override
        public String toString() {
            return "ClaimedCommand[id=" + id
                    + ", tenantId=" + tenantId
                    + ", deviceId=" + deviceId
                    + ", protocol=" + protocol
                    + ", payloadBytes=" + payload.length
                    + ", attempts=" + attempts
                    + "]";
        }
    }

    /**
     * Registra a escrita local concluída se a tentativa ainda detiver o token e
     * estiver em transmissão.
     *
     * <p>É uma única instrução condicional: não exige transação explícita e não confirma nada. Sucesso
     * significa apenas que os bytes foram entregues ao socket, nunca que o rastreador os recebeu ou executou.
     * A transição limpa o token e grava {@code sent_at}, de modo que repetir a chamada retorna falso.</p>
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param tenantId   identificador do tenant que delimita a operação
     * @param commandId  identificador do comando na fila
     * @param claimToken token que identifica a reserva vigente
     * @return verdadeiro se a transição condicional alterou a linha da tentativa
     * @throws SQLException         se a operação JDBC falhar
     * @throws NullPointerException se algum argumento obrigatório for nulo
     */
    public boolean markSent(
            Connection connection,
            UUID tenantId,
            UUID commandId,
            UUID claimToken) throws SQLException {

        Objects.requireNonNull(connection);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(commandId);
        Objects.requireNonNull(claimToken);

        // Só a tentativa dona do token e ainda em transmissão pode concluir; outro
        // emissor ou uma repetição atualiza zero linhas.
        String sql = """
                UPDATE device_commands
                SET status = 'sent',
                    sent_at = NOW(), claim_token = NULL
                WHERE tenant_id = ?
                  AND id = ?
                  AND claim_token = ?
                  AND status = 'transmitting'
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setObject(1, tenantId);
            statement.setObject(2, commandId);
            statement.setObject(3, claimToken);

            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Condiciona o início da transmissão ao estado da reserva e ao token da
     * tentativa, com proprietário, identidade da sessão e auditoria obrigatórios.
     * O chamador deve confirmar a transação antes de escrever no socket.
     *
     * <p>A linha só passa de {@code sending} para {@code transmitting} se ainda não expirou, se o comando tem
     * solicitante e chave de requisição e se o cadastro do dispositivo está habilitado, no mesmo tenant e
     * protocolo, com a identidade da sessão atual. O proprietário e o instante de início ficam gravados para
     * a recuperação posterior. Não confirma a transação.</p>
     *
     * @param connection      conexão JDBC usada pelo chamador
     * @param tenantId        identificador do tenant que delimita a operação
     * @param commandId       identificador do comando na fila
     * @param claimToken      token que identifica a reserva vigente
     * @param owner           UUID do proprietário da tentativa
     * @param sessionIdentity identidade de referência do equipamento
     * @return verdadeiro se a transição condicional alterou a linha da tentativa
     * @throws NullPointerException se qualquer argumento obrigatório for nulo
     * @throws SQLException         se a operação JDBC falhar
     */
    public boolean authorizeTransmission(Connection connection, UUID tenantId, UUID commandId,
            UUID claimToken, UUID owner, String sessionIdentity) throws SQLException {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(commandId);
        Objects.requireNonNull(claimToken);
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(sessionIdentity, "sessionIdentity");

        // A transição exige a mesma reserva, prazo vigente e auditoria completa. O
        // EXISTS confirma que o dispositivo segue habilitado na sessão informada.
        String sql = """
                UPDATE device_commands
                SET status = 'transmitting',
                    transmission_started_at = NOW(), dispatch_owner = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND claim_token = ?
                  AND status = 'sending'
                  AND expires_at > clock_timestamp()
                  AND requested_by IS NOT NULL AND request_key IS NOT NULL
                  AND EXISTS (SELECT 1 FROM devices d WHERE d.id = device_commands.device_id
                              AND d.tenant_id = device_commands.tenant_id AND d.enabled
                              AND d.protocol = device_commands.protocol
                              AND d.identity=?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setObject(1, owner);
            statement.setObject(2, tenantId);
            statement.setObject(3, commandId);
            statement.setObject(4, claimToken);
            statement.setString(5, sessionIdentity);

            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Reserva para a conexão reconhecida pelo banco e mantém o cadastro bloqueado
     * até o commit. Tenant, dispositivo e token são obrigatórios.
     *
     * <p>É a entrada usada pelo dispatcher: delega a reserva transacional e não confirma. O token recebido é
     * o da sessão admitida, não o token da tentativa, que é gerado e devolvido em {@link ClaimedCommand}.</p>
     *
     * @param connection conexão da transação que manterá a reserva
     * @param tenantId tenant proprietário do dispositivo
     * @param deviceId dispositivo cuja sessão pode receber o comando
     * @param sessionToken token da conexão admitida no cadastro
     * @return reserva elegível, ou vazio se não houver comando para essa sessão
     * @throws SQLException se a operação JDBC falhar
     * @throws IllegalStateException se a conexão estiver em autocommit
     * @throws NullPointerException se um identificador obrigatório for nulo
     */
    public Optional<ClaimedCommand> claimForSession(Connection connection, UUID tenantId, UUID deviceId,
            UUID sessionToken) throws SQLException {
        return claim(connection, Objects.requireNonNull(tenantId), Objects.requireNonNull(deviceId),
                Objects.requireNonNull(sessionToken));
    }

    /**
     * Bloqueia o dispositivo elegível e verifica novamente a fila antes de atribuir
     * o token de reserva.
     *
     * <p>Primeiro seleciona, com {@code FOR UPDATE OF c, d SKIP LOCKED}, um comando na fila para o
     * dispositivo e a sessão informados; depois repete as condições no {@code UPDATE} que incrementa {@code
     * attempts}. {@code SKIP LOCKED} faz um concorrente ignorar a linha já reservada em vez de esperar. O
     * comando e o cadastro permanecem bloqueados até o commit ou rollback do chamador.</p>
     *
     * @param connection   conexão JDBC usada pelo chamador
     * @param tenantId     identificador do tenant que delimita a operação
     * @param deviceId     identificador do equipamento cadastrado
     * @param sessionToken token da sessão admitida no cadastro
     * @return comando reservado com seu token, ou vazio se nenhum for elegível
     * @throws SQLException          se a operação JDBC falhar
     * @throws IllegalStateException se a conexão estiver em autocommit
     */
    private Optional<ClaimedCommand> claim(Connection connection, UUID tenantId, UUID deviceId, UUID sessionToken)
            throws SQLException {
        if (connection.getAutoCommit()) {
            throw new IllegalStateException(
                    "CLAIM_REQUIRES_TRANSACTION");
        }

        // A sessão admitida e o escopo de tenant/device limitam a busca; o NOT
        // EXISTS impede outro comando em voo para o mesmo dispositivo.
        String select = """
                    SELECT c.id
                    FROM device_commands c
                    JOIN devices d
                      ON d.tenant_id = c.tenant_id
                     AND d.id = c.device_id
                    WHERE c.tenant_id = ? AND c.device_id = ? AND d.command_session_token = ?
                      AND c.status = 'queued'
                      AND c.available_at <= NOW()
                      AND c.expires_at > NOW()
                      AND d.enabled = true
                      AND d.protocol = c.protocol
                      AND NOT EXISTS (SELECT 1 FROM device_commands active
                                      WHERE active.tenant_id = c.tenant_id AND active.device_id = c.device_id
                                        AND active.status IN ('sending', 'transmitting', 'sent'))
                    ORDER BY
                        c.available_at,
                        c.created_at,
                        c.id
                    FOR UPDATE OF c, d SKIP LOCKED
                    LIMIT 1
                """;

        UUID candidate;
        try (var statement = connection.prepareStatement(select)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, deviceId);
            statement.setObject(3, sessionToken);
            try (var rows = statement.executeQuery()) {
                if (!rows.next())
                    return Optional.empty();
                candidate = rows.getObject(1, UUID.class);
            }
        }

        // Após obter o bloqueio, a próxima consulta em READ COMMITTED enxerga reservas
        // recém-confirmadas. As condições são repetidas para que a reserva só ocorra
        // se o comando ainda for elegível no instante da escrita.
        String update = """
                UPDATE device_commands c SET status='sending', attempts=c.attempts+1,
                    claimed_at=now(), claim_token=?
                WHERE c.id=? AND c.tenant_id=? AND c.device_id=? AND c.status='queued' AND c.expires_at > clock_timestamp()
                AND NOT EXISTS (SELECT 1 FROM device_commands active
                                WHERE active.tenant_id=c.tenant_id AND active.device_id=c.device_id
                                  AND active.status IN ('sending','transmitting','sent'))
                RETURNING id,tenant_id,device_id,protocol,payload,attempts,claim_token
                """;
        try (var statement = connection.prepareStatement(update)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, candidate);
            statement.setObject(3, tenantId);
            statement.setObject(4, deviceId);
            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    return Optional.empty();
                }

                ClaimedCommand command = new ClaimedCommand(
                        result.getObject("id", UUID.class),
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("device_id", UUID.class),
                        result.getString("protocol"),
                        result.getBytes("payload"),
                        result.getInt("attempts"),
                        result.getObject("claim_token", UUID.class));

                // O UPDATE deve devolver no máximo uma linha; duas violariam o
                // contrato de um único comando reservado.
                if (result.next()) {
                    throw new SQLException(
                            "MULTIPLE_COMMANDS_CLAIMED");
                }

                return Optional.of(command);
            }
        }
    }

    /**
     * Motivo usado para distinguir recusa antes do envio de falha potencialmente
     * posterior à escrita.
     *
     * <p>Os dois primeiros valores encerram a reserva em {@code sending} sem transmissão; {@link
     * #TCP_WRITE_UNCERTAIN} só pode ser usado depois de a transmissão ter sido autorizada e resulta em {@code
     * delivery_unknown}.</p>
     */
    public enum Failure {
        /** Os bytes ou o protocolo divergiram da operação permitida. */
        COMMAND_POLICY_DENIED,
        /** A reserva deixou de satisfazer as condições de início da transmissão. */
        AUTHORIZATION_REJECTED,
        /** A escrita falhou com possibilidade de envio parcial. */
        TCP_WRITE_UNCERTAIN
    }

    /**
     * Registra o desfecho compatível com a etapa da falha e indica se a tentativa
     * ainda podia alterar o comando.
     *
     * <p>O estado esperado depende do motivo: {@code sending} para recusas antes da escrita e {@code
     * transmitting} para falha incerta após a autorização. A transição exige o token e o dispositivo da
     * reserva, limpa o token e grava o código do motivo. É uma única instrução e não confirma a
     * transação.</p>
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param command    comando da tentativa em curso
     * @param reason     motivo do encerramento da tentativa
     * @return verdadeiro se a transição condicional alterou a linha da tentativa
     * @throws SQLException         se a operação JDBC falhar
     * @throws NullPointerException se o comando ou o motivo for nulo
     */
    public boolean finishFailure(Connection connection, ClaimedCommand command, Failure reason)
            throws SQLException {
        // A falha incerta encerra uma transmissão já autorizada; as demais encerram
        // a reserva ainda em sending.
        boolean writing = reason == Failure.TCP_WRITE_UNCERTAIN;
        String transition = writing
                ? "status='delivery_unknown', delivery_unknown_at=now()"
                : "status='delivery_failed'";
        // O token, o dispositivo e o estado esperado delimitam a atualização e
        // impedem que um emissor obsoleto altere a tentativa.
        String sql = "UPDATE device_commands SET claim_token=NULL, error_code=?, " + transition
                + " WHERE tenant_id=? AND device_id=? AND id=? AND claim_token=? AND status=?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, reason.name());
            statement.setObject(2, command.tenantId());
            statement.setObject(3, command.deviceId());
            statement.setObject(4, command.id());
            statement.setObject(5, command.claimToken());
            statement.setString(6, writing ? "transmitting" : "sending");
            return statement.executeUpdate() == 1;
        }
    }
}
