
package software.aporia.tracking.command;

/**
 * Define os estados persistidos do comando e as transições aceitas pelo domínio.
 *
 * <p> O ciclo parte de {@link #QUEUED}. Uma tentativa passa por {@link #SENDING} (reserva), {@link
 * #TRANSMITTING} (autorização confirmada, escrita possível) e {@link #SENT} (escrita local concluída). A
 * resposta correlacionada leva a {@link #RESPONDED}; falhas anteriores ao envio levam a {@link
 * #DELIVERY_FAILED}; resultado incerto leva a {@link #DELIVERY_UNKNOWN}; prazos vencidos levam a {@link
 * #TIMEOUT}. {@link #CANCELLED} encerra uma solicitação ainda na fila. </p>
 *
 * <p> Limite semântico: {@link #SENT} não significa entrega nem execução pelo rastreador; confirma apenas a
 * escrita local. A confirmação de aplicação exige resposta de protocolo correlacionada, representada por
 * {@link #RESPONDED}. </p>
 *
 * <p> {@link #canTransitionTo} é uma validação do domínio e não persiste nada. Os repositórios JDBC repetem
 * as condições na cláusula {@code WHERE} para que a transição seja atômica sob concorrência. </p>
 */
public enum DeviceCommandStatus {

    /** A solicitação está disponível na fila até o prazo de expiração. */
    QUEUED("queued"),
    /** Uma tentativa reservou a solicitação com um token. */
    SENDING("sending"),
    /** O início da tentativa foi autorizado e a escrita pode estar em andamento. */
    TRANSMITTING("transmitting"),
    /**
     * A escrita local terminou e seu resultado foi registrado. Não confirma entrega nem execução pelo
     * rastreador.
     */
    SENT("sent"),
    /** Uma resposta foi vinculada ao comando. */
    RESPONDED("responded"),
    /**
     * O prazo venceu na fila ou durante a espera pela resposta esperada, conforme o estado de origem.
     */
    TIMEOUT("timeout"),
    /** A tentativa foi encerrada antes de transmitir o comando. */
    DELIVERY_FAILED("delivery_failed"),
    /**
     * A transmissão pode ter começado, inclusive com envio parcial, e seu resultado é incerto; não há reenvio
     * automático.
     */
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
     * @return valor persistido deste estado
     */
    public String databaseValue() {
        return databaseValue;
    }

    /**
     * Verifica se o estado atual admite a transição solicitada. É uma consulta pura: não altera o estado nem
     * consulta o banco.
     *
     * @param next estado de destino proposto
     * @return verdadeiro quando a transição solicitada é permitida; falso para
     *         {@code null} e para transições a partir de estados terminais
     */
    public boolean canTransitionTo(DeviceCommandStatus next) {

        if (next == null) {
            return false;
        }

        // Estados terminais não retornam à fila nem aceitam uma nova tentativa.
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
     * Converte o valor persistido no estado correspondente do domínio. A comparação é exata e diferencia
     * maiúsculas de minúsculas.
     *
     * @param value valor a interpretar
     * @return estado correspondente ao valor persistido
     * @throws IllegalArgumentException se o valor não corresponder a nenhum
     *                                  estado, inclusive quando for nulo
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
