package software.aporia.tracking.storage.packet;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import software.aporia.tracking.core.RawPacket;

/** Converte registros de pacotes brutos entre a representação Java e a tabela de ingestão via JDBC. */
public class JdbcRawPacketRepository implements RawPacketRepository {

    /** Endereço JDBC do banco que armazena os pacotes. */
    private final String url;
    /** Usuário informado ao driver JDBC. */
    private final String username;
    /** Credencial usada ao abrir a conexão JDBC. */
    private final String password;

    /**
     * Guarda os parâmetros usados pelo DriverManager para abrir uma conexão a cada operação.
     *
     * @param url URL JDBC do banco de pacotes
     * @param username usuário da conexão JDBC
     * @param password credencial da conexão JDBC
     */
    public JdbcRawPacketRepository(
            String url,
            String username,
            String password
    ) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    /**
     * Vincula os campos do pacote aos parâmetros do INSERT e grava os bytes da recepção.
     *
     * @param packet pacote bruto a processar
     */
    @Override
    public void save(RawPacket packet) {

        String sql = """
            INSERT INTO raw_packets (
                id, protocol, device_identity,
                payload, received_at
            ) VALUES (?, ?, ?, ?, ?)
            """;

        try (

                Connection connection = DriverManager.getConnection(
                        url, username, password
                ); 

                PreparedStatement statement
                = connection.prepareStatement(sql)) {
                    statement.setObject(1, packet.id());
                    statement.setString(2, packet.protocol());
                    statement.setString(3, packet.deviceIdentity());
                    statement.setBytes(4, packet.payload());
                    // O driver recebe um offset explícito para conservar o instante da recepção em TIMESTAMPTZ.
                    statement.setObject(
                            5,
                            packet.receivedAt().atOffset(ZoneOffset.UTC)
                    );

                    statement.executeUpdate();

                } catch (SQLException e) {
                    throw new IllegalStateException(
                            "Failed to save raw packet", e
                    );
                }
    }

    /**
     * Reconstrói o pacote bruto a partir da linha identificada pelo UUID.
     *
     * @param id identificador do registro consultado
     * @return pacote correspondente ao UUID, se encontrado
     */
    @Override
    public Optional<RawPacket> findById(UUID id) {
        String sql = """
            SELECT id, protocol, device_identity,
                   payload, received_at
            FROM raw_packets
            WHERE id = ?
            """;

        try (
                Connection connection = DriverManager.getConnection(
                        url, username, password
                ); PreparedStatement statement
                = connection.prepareStatement(sql)) {
                    statement.setObject(1, id);

                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            return Optional.empty();
                        }

                        return Optional.of(new RawPacket(
                                result.getObject("id", UUID.class),
                                result.getString("protocol"),
                                result.getString("device_identity"),
                                result.getBytes("payload"),
                                result.getObject(
                                        "received_at",
                                        OffsetDateTime.class
                                ).toInstant()
                        ));
                    }

                } catch (SQLException e) {
                    throw new IllegalStateException(
                            "Failed to find raw packet", e
                    );
                }
    }

}
