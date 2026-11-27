package software.aporia.tracking.command.dispatcher;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Localiza o canal de saída de um equipamento dentro do tenant para que o dispatcher possa escrever na sessão
 * escolhida.
 *
 * <p>
 * Contrato: {@link #connect} devolve uma fotografia da sessão atual. A implementação deve restringir a busca ao tenant
 * e ao dispositivo informados e devolver vazio quando o equipamento estiver offline. O endpoint devolvido pode ser
 * invalidado antes da escrita; {@link Endpoint#send} deve então falhar, sem migrar para outra conexão.
 * </p>
 *
 * <p>
 * Limites: o sucesso de {@code send} indica apenas escrita local; não confirma recebimento ou execução pelo rastreador,
 * e uma falha pode ocorrer após envio parcial. Não há reenvio automático nem confirmação de entrega nesta interface.
 * </p>
 */
public interface CommandTransport {

    /**
     * Sessão selecionada para uma tentativa. Sua identidade e protocolo permitem conferir o destino antes da escrita. A
     * instância representa uma conexão específica e não deve ser reutilizada para outra tentativa se tiver sido
     * invalidada.
     */
    interface Endpoint {
        /**
         * Identifica esta conexão TCP entre todas as réplicas.
         *
         * @return token da conexão selecionada
         */
        UUID sessionToken();

        /**
         * Informa o protocolo usado pela conexão selecionada.
         *
         * @return identificador do protocolo desta implementação
         */
        String protocol();

        /**
         * Informa a identidade do rastreador associado à conexão.
         *
         * @return identidade do equipamento no protocolo
         */
        String identity();

        /**
         * Escreve os bytes do comando no canal. Uma falha de E/S pode ocorrer após uma escrita parcial.
         *
         * @param payload bytes do conteúdo da mensagem
         * @throws IOException se a operação de entrada ou saída falhar
         */
        void send(byte[] payload) throws IOException;
    }

    /**
     * Busca o canal disponível para o par tenant e dispositivo. Um resultado vazio indica equipamento offline.
     *
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @return canal da sessão disponível, ou vazio se o dispositivo estiver offline
     */
    Optional<Endpoint> connect(UUID tenantId, UUID deviceId);

    /**
     * Consulta se há um canal disponível no instante da chamada. O resultado pode mudar antes de uma escrita
     * subsequente.
     *
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @return verdadeiro se a conexão está disponível localmente
     */
    default boolean isConnected(
            UUID tenantId,
            UUID deviceId) {
        return connect(tenantId, deviceId).isPresent();
    }

    /**
     * Escreve os bytes do comando no canal atual. Uma falha de E/S pode ocorrer após uma escrita parcial e não é
     * repetida automaticamente.
     *
     * @param tenantId identificador do tenant que delimita a operação
     * @param deviceId identificador do equipamento cadastrado
     * @param payload  bytes do conteúdo da mensagem
     * @throws IOException se o dispositivo estiver offline ({@code DEVICE_SESSION_NOT_AVAILABLE}) ou a escrita falhar
     */
    default void send(
            UUID tenantId,
            UUID deviceId,
            byte[] payload) throws IOException {
        connect(tenantId, deviceId).orElseThrow(() -> new IOException("DEVICE_SESSION_NOT_AVAILABLE"))
                .send(payload);
    }
}
