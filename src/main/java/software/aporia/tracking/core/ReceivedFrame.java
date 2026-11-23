
package software.aporia.tracking.core;

import java.util.Objects;

/**
 * Leva os bytes recebidos e a identidade declarada pelo rastreador até a ingestão.
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

    // Compatibilidade com os testes e chamadas existentes.
    // Uso temporário, restrito ao protocolo Suntech.
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

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
