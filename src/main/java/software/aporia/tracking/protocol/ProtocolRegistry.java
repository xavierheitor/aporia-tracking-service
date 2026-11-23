package software.aporia.tracking.protocol;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolve adaptadores por identificador ou pelo primeiro byte da conexão. Um prefixo ambíguo
 * impede a seleção automática.
 */
public final class ProtocolRegistry {

    /** Adaptadores disponíveis para seleção por identificador ou prefixo. */
    private final Map<String, ProtocolAdapter> adapters;

    /**
     * Constrói o índice de adaptadores e rejeita identificadores vazios ou repetidos.
     *
     * @param registeredAdapters adaptadores disponíveis para seleção
     */
    public ProtocolRegistry(
            Collection<? extends ProtocolAdapter> registeredAdapters
    ) {
        Objects.requireNonNull(registeredAdapters);

        Map<String, ProtocolAdapter> registry = new HashMap<>();

        for (ProtocolAdapter adapter : registeredAdapters) {
            Objects.requireNonNull(adapter);

            String id = Objects.requireNonNull(
                    adapter.protocolId()
            );

            if (id.isBlank()) {
                throw new IllegalArgumentException(
                        "Protocol ID cannot be blank"
                );
            }

            if (registry.putIfAbsent(id, adapter) != null) {
                throw new IllegalArgumentException(
                        "Duplicate protocol: " + id
                );
            }
        }

        this.adapters = Map.copyOf(registry);
    }

    /**
     * Seleciona o único adaptador que aceita o prefixo recebido. Falha quando nenhum ou vários o
     * reconhecem.
     *
     * @param first primeiro byte da conexão, interpretado sem sinal
     * @return único adaptador que aceita o prefixo
     * @throws IllegalStateException se mais de um adaptador reconhecer o prefixo
     * @throws IllegalArgumentException se nenhum protocolo reconhecer o prefixo
     */
    public ProtocolAdapter detect(int first) {
        ProtocolAdapter selected = null;
        for (var adapter : adapters.values()) {
            if (adapter.acceptsFirstByte(first)) {
                if (selected != null) throw new IllegalStateException("AMBIGUOUS_PROTOCOL_PREFIX");
                selected = adapter;
            }
        }
        if (selected == null) throw new IllegalArgumentException("UNKNOWN_PROTOCOL_PREFIX");
        return selected;
    }

    /**
     * Resolve o adaptador pelo identificador configurado.
     *
     * @param protocolId identificador do protocolo do equipamento
     * @return adaptador registrado para o protocolo
     */
    public ProtocolAdapter get(String protocolId) {

        ProtocolAdapter adapter = adapters.get(protocolId);

        if (adapter == null) {
            throw new IllegalArgumentException(
                    "Unsupported protocol: " + protocolId
            );
        }

        return adapter;
    }
}
