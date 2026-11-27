package software.aporia.tracking.worker;

import java.util.UUID;

/**
 * Descreve o desfecho de um ciclo do worker, incluindo o motivo e o prazo de nova tentativa
 * quando aplicáveis.
 *
 * <p>É um record imutável usado entre o repositório e o worker; o status determina quais campos são
 * preenchidos. O resultado é construído depois que a transação é confirmada e não carrega conexão nem objetos
 * JDBC.</p>
 *
 * @param packetId UUID da recepção, ou nulo para fila vazia
 * @param status desfecho observado na transação
 * @param errorCode motivo registrado em caso de falha, ou nulo quando não há falha
 * @param attempts número de tentativas informado em caso de falha; zero nos demais desfechos
 * @param retryDelaySeconds intervalo em segundos até a retentativa; zero quando não agendada
 */
record ProcessingResult(
        UUID packetId,
        Status status,
        String errorCode,
        int attempts,
        long retryDelaySeconds
        ) {

    /**
     * Distingue fila vazia, processamento concluído, rejeição permanente e reagendamento.
     */
    enum Status {
        /** O ciclo não encontrou pacote bruto elegível. */
        EMPTY,
        /** O pacote foi concluído nesta transação. */
        PROCESSED,
        /** A falha encerrou o processamento do pacote. */
        REJECTED,
        /** O pacote recebeu novo prazo para uma tentativa posterior. */
        RETRY_SCHEDULED
    }

    /**
     * Indica que a consulta não encontrou um pacote elegível.
     *
     * @return resultado do ciclo com o estado e os dados aplicáveis
     */
    static ProcessingResult empty() {
        return new ProcessingResult(
                null, Status.EMPTY, null, 0, 0
        );
    }

    /**
     * Registra a conclusão do processamento para o pacote selecionado.
     *
     * @param packetId identificador da recepção bruta
     * @return resultado do ciclo com o estado e os dados aplicáveis
     */
    static ProcessingResult processed(UUID packetId) {
        return new ProcessingResult(
                packetId, Status.PROCESSED, null, 0, 0
        );
    }

    /**
     * Preserva o motivo e a quantidade de tentativas na rejeição definitiva.
     *
     * @param packetId identificador da recepção bruta
     * @param errorCode código que identifica o motivo da falha
     * @param attempts número de tentativas associado à operação
     * @return resultado do ciclo com o estado e os dados aplicáveis
     */
    static ProcessingResult rejected(
            UUID packetId,
            String errorCode,
            int attempts
    ) {
        return new ProcessingResult(
                packetId, Status.REJECTED, errorCode, attempts, 0
        );
    }

    /**
     * Relaciona o motivo da falha ao atraso programado para a próxima tentativa.
     *
     * @param packetId identificador da recepção bruta
     * @param errorCode código que identifica o motivo da falha
     * @param attempts número de tentativas associado à operação
     * @param delaySeconds atraso em segundos até a próxima tentativa
     * @return resultado do ciclo com o estado e os dados aplicáveis
     */
    static ProcessingResult retryScheduled(
            UUID packetId,
            String errorCode,
            int attempts,
            long delaySeconds
    ) {
        return new ProcessingResult(
                packetId,
                Status.RETRY_SCHEDULED,
                errorCode,
                attempts,
                delaySeconds
        );
    }
}
