package software.aporia.tracking.storage.telemetry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Atualiza a posição mais recente do ativo segundo a ordem temporal das leituras.
 *
 * <p>Usa a conexão e a transação do chamador, restringe pelo tenant e grava uma
 * linha por ativo ({@code PRIMARY KEY (tenant_id, asset_id)}). A posição
 * referenciada precisa já existir no histórico, pois a chave estrangeira é
 * validada no mesmo commit.</p>
 */
public class LastPositionRepository {

    /**
     * Insere a primeira posição ou avança o marcador para uma leitura mais recente. Retorna se
     * houve alteração.
     *
     * <p>A atualização condicional só vence quando o {@code recorded_at} recebido
     * é estritamente maior que o gravado; leituras empatadas ou mais antigas não
     * alteram a linha e produzem falso. O {@code received_at} acompanha a posição
     * vencedora. Tudo ocorre na transação do chamador, sem confirmação própria.</p>
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param tenantId identificador do tenant que delimita a operação
     * @param assetId identificador do ativo associado ao equipamento
     * @param positionId identificador da posição no histórico
     * @param recordedAt instante da leitura no equipamento
     * @param receivedAt instante em que o gateway recebeu os bytes
     * @return verdadeiro quando a última posição do ativo foi inserida ou atualizada
     * @throws SQLException se a operação JDBC falhar
     */
    public boolean update(
            Connection connection,
            UUID tenantId,
            UUID assetId,
            UUID positionId,
            Instant recordedAt,
            Instant receivedAt
    ) throws SQLException {
        // Pacotes antigos podem chegar depois da reconexão. Só uma leitura mais nova avança o marcador.
        String sql = """
            INSERT INTO last_positions (
                tenant_id,
                asset_id,
                position_id,
                recorded_at,
                received_at
            )
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, asset_id)
            DO UPDATE SET
                position_id = EXCLUDED.position_id,
                recorded_at = EXCLUDED.recorded_at,
                received_at = EXCLUDED.received_at
            WHERE EXCLUDED.recorded_at >
                  last_positions.recorded_at
            """;
        try (PreparedStatement statement
                = connection.prepareStatement(sql)) {

            statement.setObject(1, tenantId);
            statement.setObject(2, assetId);
            statement.setObject(3, positionId);

            statement.setObject(
                    4,
                    java.sql.Timestamp.from(recordedAt)
            );

            statement.setObject(
                    5,
                    java.sql.Timestamp.from(receivedAt)
            );

            return statement.executeUpdate() > 0;
        }
    }
}
