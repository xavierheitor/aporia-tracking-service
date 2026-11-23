package software.aporia.tracking.protocol;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Localiza o decodificador pela versão gravada na ingestão, inclusive para pacotes processados
 * posteriormente.
 */
public final class DecoderRegistry {

    private final Map<String, TelemetryDecoder> decoders;

    /** Indexa os decodificadores por versão e rejeita colisões no registro. */
    public DecoderRegistry(
            Collection<? extends TelemetryDecoder> registeredDecoders
    ) {
        Objects.requireNonNull(registeredDecoders);

        Map<String, TelemetryDecoder> registry = new HashMap<>();

        for (TelemetryDecoder decoder : registeredDecoders) {
            Objects.requireNonNull(decoder);

            String version = Objects.requireNonNull(
                    decoder.decoderVersion()
            );

            if (version.isBlank()) {
                throw new IllegalArgumentException(
                        "Decoder version cannot be blank"
                );
            }

            if (registry.putIfAbsent(version, decoder) != null) {
                throw new IllegalArgumentException(
                        "Duplicate decoder version: " + version
                );
            }
        }
        this.decoders = Map.copyOf(registry);

    }

    /** Resolve a versão gravada no pacote bruto. Uma versão desconhecida impede a decodificação. */
    public TelemetryDecoder get(String decoderVersion) {

        TelemetryDecoder decoder = decoders.get(decoderVersion);

        if (decoder == null) {
            throw new IllegalArgumentException(
                    "Decoder unavailable: " + decoderVersion
            );
        }

        return decoder;
    }
}
