package software.aporia.tracking.command.dispatcher;

import java.util.Optional;
import java.io.IOException;
import java.util.UUID;

/**
 * Localiza o canal de saída de um equipamento dentro do tenant para que o dispatcher possa
 * escrever na sessão escolhida.
 */
public interface CommandTransport {

    /**
     * Sessão selecionada para uma tentativa. Sua identidade e protocolo permitem conferir o
     * destino antes da escrita.
     */
    interface Endpoint {
        /** Informa o protocolo usado pela conexão selecionada. */
        String protocol();
        /** Informa a identidade do rastreador associado à conexão. */
        String identity();
        /**
         * Escreve os bytes do comando no canal. Uma falha de E/S pode ocorrer após uma escrita
         * parcial.
         */
        void send(byte[] payload) throws IOException;
    }

    /**
     * Busca o canal disponível para o par tenant e dispositivo. Um resultado vazio indica
     * equipamento offline.
     */
    Optional<Endpoint> connect(UUID tenantId, UUID deviceId);

    /** Consulta se há um canal disponível no instante da chamada. */
    default boolean isConnected(
            UUID tenantId,
            UUID deviceId
    ) {
        return connect(tenantId, deviceId).isPresent();
    }

    /** Escreve os bytes do comando no canal. Uma falha de E/S pode ocorrer após uma escrita parcial. */
    default void send(
            UUID tenantId,
            UUID deviceId,
            byte[] payload
    ) throws IOException {
        connect(tenantId, deviceId).orElseThrow(() -> new IOException("DEVICE_SESSION_NOT_AVAILABLE"))
                .send(payload);
    }
}
