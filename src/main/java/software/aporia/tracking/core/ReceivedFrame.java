package software.aporia.tracking.core;

import java.util.Objects;

/**
 * Leva os bytes recebidos e a identidade declarada pelo rastreador até a
 * ingestão.
 *
 * @param protocol identificador do protocolo no cadastro
 * @param identity identidade declarada pelo equipamento no protocolo
 * @param payload bytes originais do quadro ou comando
 * @param decoderVersion versão que o worker deve usar ao interpretar o conteúdo
 */
public record ReceivedFrame(
        String protocol,
        String identity,
        byte[] payload,
        String decoderVersion
        ) {

    /**
     * Valida os campos necessários à ingestão e copia o payload para que a leitura seguinte do
     * socket não altere o quadro.
     *
     * @param protocol identificador do protocolo do equipamento
     * @param identity identidade declarada pelo rastreador no protocolo
     * @param payload bytes do conteúdo da mensagem
     * @param decoderVersion versão do decodificador vinculada ao pacote
     * @throws NullPointerException se o payload for nulo
     * @throws IllegalArgumentException se protocolo, identidade ou versão estiverem ausentes, ou se o tamanho for inválido
     */
    public ReceivedFrame {

        if (protocol == null || protocol.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid protocol"
            );
        }

        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid identity"
            );
        }

        if (decoderVersion == null
                || decoderVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid decoder version"
            );
        }

        Objects.requireNonNull(payload, "payload");

        if (payload.length == 0 || payload.length > 65536) {
            throw new IllegalArgumentException(
                    "Invalid payload size"
            );
        }

        payload = payload.clone();
    }

    /**
     * Construtor do ReceivedFrame. Obrigatório fornecer um identificador de
     * protocolo, uma identidade de equipamento e um payload. O payload deve ser
     * um array de bytes válido e não nulo.
     *
     * @param protocol identificador do protocolo no cadastro
     * @param identity identidade declarada pelo equipamento no protocolo
     * @param payload bytes originais do quadro ou comando
     * @throws IllegalArgumentException se o identificador do protocolo for nulo
     * ou vazio
     * @throws IllegalArgumentException se a identidade do equipamento for nula
     * ou vazia
     * @throws NullPointerException se o payload for nulo
     * @throws IllegalArgumentException se o protocolo não for Suntech ou o payload tiver tamanho inválido
     * @deprecated Prefira o construtor que recebe a versão do decodificador. Esta sobrecarga aceita somente Suntech.
     */
    @Deprecated
    public ReceivedFrame(
            String protocol,
            String identity,
            byte[] payload
    ) {
        this(
                protocol,
                identity,
                payload,
                legacyDecoderVersion(protocol)
        );
    }

    /**
     * Resolve a versão usada pelo construtor legado, restrito aos quadros Suntech.
     *
     * @param protocol identificador do protocolo do equipamento
     * @return versão usada para interpretar os bytes persistidos
     */
    private static String legacyDecoderVersion(
            String protocol
    ) {
        if (!"suntech".equals(protocol)) {
            throw new IllegalArgumentException(
                    "Decoder version required for protocol: "
                    + protocol
            );
        }

        return "suntech-ascii-v3";
    }

    /**
     * Retorna uma cópia do payload original.
     *
     * @return uma cópia do payload original
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
