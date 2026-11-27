package software.aporia.tracking.worker;

import java.time.Duration;

/**
 * Determina o intervalo de espera usado pelo worker conforme o número da tentativa de processamento.
 *
 * <p>A política é estática e compartilhada: 10 s, 30 s, 60 s e 120 s para a primeira, segunda, terceira e
 * quarta tentativa; a partir da quinta o worker rejeita o pacote. O atraso é aplicado pelo banco em {@code
 * available_at} e não bloqueia a thread do worker.</p>
 */
public final class RetryPolicy{
    /** Quantidade de tentativas a partir da qual o worker rejeita o pacote. */
    public static final int MAX_ATTEMPTS = 5;

    /**
     * Impede a instanciação; os intervalos são calculados por um método estático.
     */
    private RetryPolicy(){
    }

    /**
     * Calcula o atraso até o próximo processamento a partir do número da tentativa.
     *
     * <p>O valor recebido é a tentativa que será registrada: 1 a 4 usam a tabela; qualquer outro valor usa o
     * atraso máximo de 120 segundos.</p>
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