package software.aporia.tracking.storage;

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

public class JdbcRawPacketRepository implements RawPacketRepository {

    private final String url;
    private final String username;
    private final String password;

    public JdbcRawPacketRepository(
            String url,
            String username,
            String password
    ) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    @Override
    public void save(RawPacket packet) {
        //String sql de insert
        String sql = """
            INSERT INTO raw_packets (
                id, protocol, device_identity,
                payload, received_at
            ) VALUES (?, ?, ?, ?, ?)
            """;

        try (
                //pede a conexao com o banco de dados
                Connection connection = DriverManager.getConnection(
                        url, username, password
                ); 

                //escreve o sql com os dados corretos
                PreparedStatement statement
                = connection.prepareStatement(sql)) {
                    statement.setObject(1, packet.id());
                    statement.setString(2, packet.protocol());
                    statement.setString(3, packet.deviceIdentity());
                    statement.setBytes(4, packet.payload());
                    statement.setObject(
                            5,
                            packet.receivedAt().atOffset(ZoneOffset.UTC)
                    );
                    //executa o sql no banco
                    statement.executeUpdate();

                } catch (SQLException e) {
                    throw new IllegalStateException(
                            "Failed to save raw packet", e
                    );
                }
    }

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
