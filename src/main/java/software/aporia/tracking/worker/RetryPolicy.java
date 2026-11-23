package software.aporia.tracking.worker;

import java.time.Duration;

/** Determina o intervalo de espera usado pelo worker conforme o número da tentativa de processamento. */
public final class RetryPolicy{
    public static final int MAX_ATTEMPTS = 5;

    private RetryPolicy(){
    }

    /** Calcula o atraso até o próximo processamento a partir do número da tentativa. */
    public static Duration delayForAttempt(int attempt){
        return switch(attempt){
            case 1 -> Duration.ofSeconds(10);
            case 2 -> Duration.ofSeconds(30);
            case 3 -> Duration.ofSeconds(60);
            default -> Duration.ofSeconds(120);
        };
    }
}