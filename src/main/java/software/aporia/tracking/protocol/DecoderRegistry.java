package software.aporia.tracking.protocol;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Localiza o decodificador pela versão gravada na ingestão, inclusive para pacotes processados
 * posteriormente.
 *
 * <p>A chave é a versão do decodificador, não o protocolo: assim um pacote antigo continua legível mesmo
 * depois de o formato evoluir. O índice é imutável após a construção e pode ser compartilhado entre threads;
 * não existe fallback para versões desconhecidas.</p>
 */
public final class DecoderRegistry {

    /** Decodificadores indexados pela versão persistida junto ao pacote. */
    private final Map<String, TelemetryDecoder> decoders;

    /**
     * Indexa os decodificadores por versão e rejeita colisões no registro.
     *
     * @param registeredDecoders decodificadores indexados por versão
     * @throws NullPointerException se a coleção, um decodificador ou sua versão for nulo
     * @throws IllegalArgumentException se alguma versão estiver em branco ou repetida
     */
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

            // Versão em branco não pode ser gravada no pacote nem usada como chave.
            if (version.isBlank()) {
                throw new IllegalArgumentException(
                        "Decoder version cannot be blank"
                );
            }

            // A mesma versão não pode apontar para duas implementações diferentes.
            if (registry.putIfAbsent(version, decoder) != null) {
                throw new IllegalArgumentException(
                        "Duplicate decoder version: " + version
                );
            }
        }
        // O índice fica imutável para que a versão de um pacote sempre resolva
        // para a mesma implementação durante a vida desta instância.
        this.decoders = Map.copyOf(registry);

    }

    /**
     * Versões elegíveis para reserva por esta instância do worker.
     *
     * @return conjunto imutável com as versões registradas
     */
    public java.util.Set<String> versions() {
        // A visão de chaves do mapa imutável acompanha o índice e não aceita alterações.
        return decoders.keySet();
    }

    /**
     * Resolve a versão gravada no pacote bruto. Uma versão desconhecida impede a decodificação.
     *
     * @param decoderVersion versão do decodificador vinculada ao pacote
     * @return decodificador associado à versão
     * @throws IllegalArgumentException se a versão não estiver registrada
     */
    public TelemetryDecoder get(String decoderVersion) {

        // Sem fallback: decodificar com outra versão poderia interpretar os bytes de forma errada.
        TelemetryDecoder decoder = decoders.get(decoderVersion);

        if (decoder == null) {
            throw new IllegalArgumentException(
                    "Decoder unavailable: " + decoderVersion
            );
        }

        return decoder;
    }
}
