package software.aporia.tracking.protocol;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Seleciona o validador de comandos a partir do protocolo solicitado. */
public final class CommandValidatorRegistry {

    /** Validadores de comandos indexados pelo protocolo. */
    private final Map<String, CommandValidator> validators;

    /**
     * Indexa os validadores e rejeita conflitos ou protocolos sem identificador.
     *
     * @param registered validadores de comandos disponíveis
     */
    public CommandValidatorRegistry(
            Collection<? extends CommandValidator> registered
    ) {
        Objects.requireNonNull(registered);
        Map<String, CommandValidator> map = new HashMap<>();

        for (CommandValidator validator : registered) {
            Objects.requireNonNull(validator);

            String protocol = Objects.requireNonNull(validator.protocolId());

            if (protocol.isBlank()) {
                throw new IllegalArgumentException(
                        "INVALID_PROTOCOL_ID"
                );
            }

            if (map.putIfAbsent(protocol, validator) != null) {
                throw new IllegalArgumentException(
                        "DUPLICATE_COMMAND_VALIDATOR: " + protocol
                );
            }
        }

        validators = Map.copyOf(map);
    }

    /**
     * Valida os bytes pela gramática do protocolo selecionado.
     *
     * @param protocol identificador do protocolo do equipamento
     * @param payload bytes do conteúdo da mensagem
     */
    public void validate(String protocol, byte[] payload) {
        CommandValidator validator = validators.get(protocol);

        if (validator == null) {
            throw new IllegalArgumentException(
                    "UNSUPPORTED_COMMAND_PROTOCOL: " + protocol
            );
        }

        validator.validate(payload);
    }

    /**
     * Fornece ao validador a identidade esperada para conferir o destinatário.
     *
     * @param protocol identificador do protocolo do equipamento
     * @param payload bytes do conteúdo da mensagem
     * @param expectedIdentity identidade de referência do equipamento
     */
    public void validate(
            String protocol,
            byte[] payload,
            String expectedIdentity
    ) {
        CommandValidator validator = validators.get(protocol);

        if (validator == null) {
            throw new IllegalArgumentException(
                    "UNSUPPORTED_COMMAND_PROTOCOL: " + protocol
            );
        }

        validator.validate(payload, expectedIdentity);
    }

}
