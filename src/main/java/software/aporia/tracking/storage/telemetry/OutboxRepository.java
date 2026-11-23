package software.aporia.tracking.storage.telemetry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Registra eventos de posição na transação do worker para disponibilizá-los aos consumidores após
 * o commit.
 */
public class OutboxRepository {

    /**
     * Insere o evento associado à posição na mesma transação em que o worker atualiza o histórico.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param tenantId identificador do tenant que delimita a operação
     * @param assetId identificador do ativo associado ao equipamento
     * @param positionId identificador da posição no histórico
     * @return UUID do evento inserido na outbox
     * @throws SQLException se a operação JDBC falhar
     */
    public UUID savePositionEvent(
            Connection connection,
            UUID tenantId,
            UUID assetId,
            UUID positionId
    ) throws SQLException {

        UUID eventId = UUID.randomUUID();

        String sql = """
            INSERT INTO outbox_events (
                id,
                tenant_id,
                event_type,
                aggregate_id,
                payload
            )
            VALUES (
                ?, ?, 'tracking.position.created', ?,
                jsonb_build_object(
                    'positionId', ?::text,
                    'assetId', ?::text
                )
            )
            """;

        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setObject(1, eventId);
            statement.setObject(2, tenantId);
            statement.setObject(3, assetId);
            statement.setObject(4, positionId);
            statement.setObject(5, assetId);

            statement.executeUpdate();
        }

        return eventId;
    }
}
