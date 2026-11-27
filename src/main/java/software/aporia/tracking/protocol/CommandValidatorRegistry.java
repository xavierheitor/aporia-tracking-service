package software.aporia.tracking.protocol;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Seleciona o validador de comandos a partir do protocolo solicitado.
 *
 * <p>O índice é montado uma vez no construtor e não muda depois; a instância pode ser compartilhada entre
 * threads. O registro apenas escolhe a gramática: autorização, vínculo com a sessão e política de execução
 * ficam com quem solicita o comando.</p>
 */
public final class CommandValidatorRegistry {

    /** Validadores de comandos indexados pelo protocolo. */
    private final Map<String, CommandValidator> validators;

    /**
     * Indexa os validadores e rejeita conflitos ou protocolos sem identificador.
     *
     * @param registered validadores de comandos disponíveis
     * @throws NullPointerException se a coleção, um validador ou seu protocolo for nulo
     * @throws IllegalArgumentException se algum protocolo estiver em branco ou repetido
     */
    public CommandValidatorRegistry(
            Collection<? extends CommandValidator> registered
    ) {
        Objects.requireNonNull(registered);
        Map<String, CommandValidator> map = new HashMap<>();

        for (CommandValidator validator : registered) {
            Objects.requireNonNull(validator);

            String protocol = Objects.requireNonNull(validator.protocolId());

            // Um protocolo em branco não serve de chave de busca.
            if (protocol.isBlank()) {
                throw new IllegalArgumentException(
                        "INVALID_PROTOCOL_ID"
                );
            }

            // putIfAbsent falha quando dois validadores disputam o mesmo protocolo.
            if (map.putIfAbsent(protocol, validator) != null) {
                throw new IllegalArgumentException(
                        "DUPLICATE_COMMAND_VALIDATOR: " + protocol
                );
            }
        }

        // A cópia imutável desacopla o índice do mapa temporário e impede alterações posteriores.
        validators = Map.copyOf(map);
    }

    /**
     * Valida os bytes pela gramática do protocolo selecionado.
     *
     * @param protocol identificador do protocolo do equipamento
     * @param payload bytes do conteúdo da mensagem
     * @throws IllegalArgumentException se não houver validador para o protocolo ou se o payload
     * for rejeitado pela gramática
     */
    public void validate(String protocol, byte[] payload) {
        validatorFor(protocol).validate(payload);
    }

    /**
     * Fornece ao validador a identidade esperada para conferir o destinatário.
     *
     * @param protocol identificador do protocolo do equipamento
     * @param payload bytes do conteúdo da mensagem
     * @param expectedIdentity identidade de referência do equipamento
     * @throws IllegalArgumentException se não houver validador para o protocolo, se o payload for
     * rejeitado pela gramática ou se o destinatário não corresponder
     */
    public void validate(
            String protocol,
            byte[] payload,
            String expectedIdentity
    ) {
        validatorFor(protocol).validate(payload, expectedIdentity);
    }

    /**
     * Resolve o mesmo validador para as duas formas de validação pública.
     *
     * @param protocol protocolo solicitado pelo chamador
     * @return validador registrado
     * @throws IllegalArgumentException se o protocolo não tiver validador
     */
    private CommandValidator validatorFor(String protocol) {
        // Não há fallback: um protocolo desconhecido não pode ser validado por outra gramática.
        CommandValidator validator = validators.get(protocol);
        if (validator == null) {
            throw new IllegalArgumentException("UNSUPPORTED_COMMAND_PROTOCOL: " + protocol);
        }
        return validator;
    }

}
