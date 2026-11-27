package software.aporia.tracking.core;

import java.util.Objects;

/**
 * Leva os bytes recebidos e a identidade declarada pelo rastreador até a
 * ingestão.
 *
 * <p>O payload é copiado na criação e em cada leitura, por isso o chamador pode
 * reutilizar o buffer do socket. A versão do decodificador acompanha os bytes
 * para que o worker aplique a mesma interpretação mesmo se o processamento
 * acontecer depois. A identidade é apenas a declarada pelo equipamento; a
 * ingestão ainda precisa confrontá-la com o cadastro. A instância é imutável e
 * pode ser compartilhada entre threads.</p>
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
     * @throws IllegalArgumentException se protocolo, identidade ou versão estiverem
     * ausentes, ou se o tamanho for inválido
     */
    public ReceivedFrame {

        // Protocolo, identidade e versão são textos obrigatórios: em branco não
        // servem como chave de busca nem como versão de decodificação.
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

        // Quadro vazio não tem o que decodificar; o teto de 64 KiB limita a memória por pacote.
        if (payload.length == 0 || payload.length > 65536) {
            throw new IllegalArgumentException(
                    "Invalid payload size"
            );
        }

        // O array recebido pode ser reutilizado ou alterado pelo chamador; a cópia preserva o quadro.
        payload = payload.clone();
    }

    /**
     * Cria um quadro legado assumindo a versão {@code suntech-ascii-v3}.
     *
     * <p>Existe apenas para compatibilidade: como não recebe a versão, só pode representar quadros
     * Suntech.</p>
     *
     * @param protocol identificador do protocolo no cadastro
     * @param identity identidade declarada pelo equipamento no protocolo
     * @param payload bytes originais do quadro ou comando
     * @throws NullPointerException se o payload for nulo
     * @throws IllegalArgumentException se protocolo ou identidade estiverem
     * ausentes, se o protocolo não for Suntech ou se o payload tiver tamanho inválido
     * @deprecated Prefira o construtor que recebe a versão do decodificador.
     * Esta sobrecarga aceita somente Suntech.
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
     * @throws IllegalArgumentException se o protocolo não for Suntech
     */
    private static String legacyDecoderVersion(
            String protocol
    ) {
        // Sem uma versão conhecida o worker não teria como escolher o decodificador;
        // por isso o construtor legado não aceita inventar uma para outro protocolo.
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
