package software.aporia.tracking.storage.telemetry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import software.aporia.tracking.core.Telemetry;

/** Grava a posição geográfica da leitura com seus vínculos ao tenant, ao equipamento e ao ativo. */
public class PositionRepository {

    /**
     * Grava uma leitura no histórico espacial usando a conexão da transação do chamador.
     *
     * @param connection conexão JDBC usada pelo chamador
     * @param tenantId identificador do tenant que delimita a operação
     * @param assetId identificador do ativo associado ao equipamento
     * @param deviceId identificador do equipamento cadastrado
     * @param messageId identificador da mensagem processada
     * @param readingIndex índice da leitura dentro do pacote
     * @param telemetry leitura interpretada pelo decodificador
     * @param receivedAt instante em que o gateway recebeu os bytes
     * @return UUID da posição inserida
     * @throws SQLException se a operação JDBC falhar
     * @throws IllegalArgumentException se a leitura não contiver posição válida
     */
    public UUID save(
            Connection connection,
            UUID tenantId,
            UUID assetId,
            UUID deviceId,
            UUID messageId, int readingIndex,
            Telemetry telemetry,
            Instant receivedAt
    ) throws SQLException {
        if (!telemetry.hasPosition()) {
            throw new IllegalArgumentException("Telemetry does not contain a valid position");
        }

        UUID positionId = UUID.randomUUID();

        String sql = """
            INSERT INTO positions (
                id,
                tenant_id,
                asset_id,
                device_id,
                message_id,
                recorded_at,
                received_at,
                geom,
                speed_kph,
                heading,
                satellites,
                ignition, reading_index
            )
            VALUES (
                ?, ?, ?, ?, ?, ?, ?,
                ST_SetSRID(ST_MakePoint(?, ?), 4326),
                ?, ?, ?, ?, ?
            )
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, positionId);
            statement.setObject(2, tenantId);
            statement.setObject(3, assetId);
            statement.setObject(4, deviceId);
            statement.setObject(5, messageId);

            statement.setObject(
                    6,
                    java.sql.Timestamp.from(telemetry.recordedAt())
            );

            statement.setObject(
                    7,
                    java.sql.Timestamp.from(receivedAt)
            );

            // O PostGIS recebe X antes de Y. Em WGS 84 essa ordem corresponde a longitude e latitude.
            statement.setDouble(8, telemetry.longitude());
            statement.setDouble(9, telemetry.latitude());

            statement.setObject(10, telemetry.speedKph());
            statement.setObject(11, telemetry.heading());
            statement.setObject(12, telemetry.satellites());
            statement.setObject(13, telemetry.ignition());

            statement.setInt(14, readingIndex);
            statement.executeUpdate();
        }

        return positionId;
    }

}
