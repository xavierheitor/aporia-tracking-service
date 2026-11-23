package software.aporia.tracking.worker;

import java.time.Duration;

/** Determina o intervalo de espera usado pelo worker conforme o número da tentativa de processamento. */
public final class RetryPolicy{
    /** Quantidade de tentativas a partir da qual o worker rejeita o pacote. */
    public static final int MAX_ATTEMPTS = 5;

    /**
     * Restringe a consulta dos intervalos de retentativa à política estática compartilhada.
     */
    private RetryPolicy(){
    }

    /**
     * Calcula o atraso até o próximo processamento a partir do número da tentativa.
     *
     * @param attempt número de tentativas associado à operação
     * @return intervalo até que o pacote possa voltar a ser processado
     */
    public static Duration delayForAttempt(int attempt){
        return switch(attempt){
            case 1 -> Duration.ofSeconds(10);
            case 2 -> Duration.ofSeconds(30);
            case 3 -> Duration.ofSeconds(60);
            default -> Duration.ofSeconds(120);
        };
    }
}