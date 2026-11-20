package software.aporia.tracking.worker;

import java.time.Duration;

public final class RetryPolicy{
    public static final int MAX_ATTEMPTS = 5;

    private RetryPolicy(){
    }

    public static Duration delayForAttempt(int attempt){
        return switch(attempt){
            case 1 -> Duration.ofSeconds(10);
            case 2 -> Duration.ofSeconds(30);
            case 3 -> Duration.ofSeconds(60);
            default -> Duration.ofSeconds(120);
        };
    }
}