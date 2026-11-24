
package software.aporia.tracking.command;

/**
 * Define os estados persistidos do comando e as transições aceitas pelo
 * domínio.
 */
public enum DeviceCommandStatus {

    /** A solicitação está disponível na fila até o prazo de expiração. */
    QUEUED("queued"),
    /** Uma tentativa reservou a solicitação com um token. */
    SENDING("sending"),
    /** O início da tentativa foi autorizado e a escrita pode estar em andamento. */
    TRANSMITTING("transmitting"),
    /** A escrita local terminou e seu resultado foi registrado. */
    SENT("sent"),
    /** Uma resposta foi vinculada ao comando. */
    RESPONDED("responded"),
    /** O prazo venceu antes do envio ou da resposta esperada. */
    TIMEOUT("timeout"),
    /** A tentativa foi encerrada antes de transmitir o comando. */
    DELIVERY_FAILED("delivery_failed"),
    /** A transmissão pode ter começado e seu resultado é incerto. */
    DELIVERY_UNKNOWN("delivery_unknown"),
    /** A solicitação foi cancelada. */
    CANCELLED("cancelled");

    /** Representação do estado nas linhas de comandos do PostgreSQL. */
    private final String databaseValue;

    /**
     * Vincula cada estado do domínio ao texto persistido na coluna de status.
     *
     * @param databaseValue valor textual persistido para o estado
     */
    DeviceCommandStatus(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    /**
     * Retorna o valor textual usado na coluna de estado.
     *
     * @return representação textual usada no banco
     */
    public String databaseValue() {
        return databaseValue;
    }

    /**
     * Verifica se o estado atual admite a transição solicitada.
     *
     * @param next estado de destino proposto
     * @return verdadeiro quando a transição solicitada é permitida
     */
    public boolean canTransitionTo(DeviceCommandStatus next) {

        if (next == null) {
            return false;
        }

        return switch (this) {

            case QUEUED ->
                next == SENDING
                        || next == TIMEOUT
                        || next == CANCELLED;

            case SENDING ->
                next == TRANSMITTING
                        || next == DELIVERY_FAILED
                        || next == DELIVERY_UNKNOWN;

            case TRANSMITTING ->
                next == SENT
                        || next == DELIVERY_UNKNOWN;

            case SENT ->
                next == RESPONDED
                        || next == TIMEOUT;

            case TIMEOUT, DELIVERY_UNKNOWN ->
                next == RESPONDED;

            case RESPONDED, DELIVERY_FAILED, CANCELLED ->
                false;
        };
    }

    /**
     * Converte o valor persistido no estado correspondente do domínio.
     *
     * @param value valor a interpretar
     * @return estado correspondente ao valor persistido
     */
    public static DeviceCommandStatus fromDatabase(
            String value) {

        for (DeviceCommandStatus status : values()) {

            if (status.databaseValue.equals(value)) {
                return status;
            }
        }

        throw new IllegalArgumentException(
                "UNKNOWN_COMMAND_STATUS: " + value);
    }
}
