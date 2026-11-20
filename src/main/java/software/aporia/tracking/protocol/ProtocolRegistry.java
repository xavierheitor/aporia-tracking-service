package software.aporia.tracking.protocol;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class ProtocolRegistry {

    private final Map<String, ProtocolAdapter> adapters;

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
