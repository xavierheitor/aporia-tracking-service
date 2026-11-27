package software.aporia.tracking.observability.errors;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

import org.jspecify.annotations.NonNull;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.sentry.SentryEvent;
import io.sentry.protocol.SentryException;

/**
 * Limita o envio de exceções recorrentes ao serviço externo de error tracking.
 *
 * <p>A assinatura combina módulo, tipo e o quadro mais próximo da aplicação em cada exceção. O primeiro
 * evento de uma assinatura é aceito; os seguintes ficam suprimidos enquanto a entrada não expirar. O cache
 * Caffeine é concorrente, tem tamanho máximo fixo e expira após o intervalo configurado.</p>
 *
 * <p>Não interfere nos logs locais da aplicação: apenas decide se o evento será enviado ao Sentry.</p>
 */
public final class ErrorEventLimiter {

    /**
     * Assinaturas já admitidas na janela atual. O valor não é lido; a presença da chave é o que suprime a
     * repetição. A cardinalidade é limitada pelo tamanho máximo do cache.
     */
    private final Cache<String, @NonNull Boolean> recentEvents;

    /**
     * Cria o cache de deduplicação compartilhado pela instância.
     *
     * @param interval          janela durante a qual a mesma assinatura é suprimida
     * @param maximumSignatures número máximo de assinaturas mantidas no cache
     * @throws NullPointerException     se o intervalo for nulo
     * @throws IllegalArgumentException se o tamanho máximo for negativo
     */
    public ErrorEventLimiter(
            Duration interval,
            long maximumSignatures) {

        this.recentEvents = Caffeine.newBuilder()
                .expireAfterWrite(Objects.requireNonNull(interval))
                .maximumSize(maximumSignatures)
                .build();
    }

    /**
     * Decide se o evento deve ser transmitido ao Sentry.
     *
     * <p>Evento sem exceção é descartado e não consome entrada no cache. A primeira ocorrência de cada
     * assinatura é admitida; a inserção atômica garante que apenas uma thread concorrente seja aceita para a
     * mesma assinatura.</p>
     *
     * @param event evento montado pelo Sentry antes do envio
     * @return verdadeiro apenas na primeira ocorrência da assinatura dentro da janela
     * @throws NullPointerException se o evento for nulo
     */
    public boolean shouldSend(SentryEvent event) {

        List<SentryException> exceptions = event.getExceptions();

        if (exceptions == null || exceptions.isEmpty()) {
            // Sem exceção não há assinatura estável; o evento é descartado pelo chamador.
            return false;
        }

        String signature = signatureOf(exceptions);

        // putIfAbsent é atômico: somente uma thread concorrente admite a mesma assinatura.
        return recentEvents.asMap()
                .putIfAbsent(signature, true) == null;
    }

    /**
     * Monta a chave de deduplicação a partir das exceções do evento.
     *
     * <p>Para cada exceção, inclui módulo, tipo e o primeiro quadro encontrado de baixo para cima cujo módulo
     * pertença a {@code software.aporia.tracking}. A assinatura é usada apenas em memória; não é persistida
     * nem enviada.</p>
     *
     * @param exceptions exceções na ordem em que o Sentry as registrou
     * @return assinatura combinada com {@code |}
     */
    private String signatureOf(
            List<SentryException> exceptions) {

        StringJoiner signature = new StringJoiner("|");

        for (SentryException exception : exceptions) {

            signature.add(String.valueOf(exception.getModule()));
            signature.add(String.valueOf(exception.getType()));

            var stacktrace = exception.getStacktrace();

            if (stacktrace == null
                    || stacktrace.getFrames() == null) {
                continue;
            }

            var frames = stacktrace.getFrames();

            // O quadro mais próximo da aplicação é buscado do fim para o começo da pilha.
            for (int i = frames.size() - 1; i >= 0; i--) {

                var frame = frames.get(i);
                String module = frame.getModule();

                if (module != null
                        && module.startsWith(
                                "software.aporia.tracking.")) {

                    signature.add(module);
                    signature.add(String.valueOf(frame.getFunction()));
                    signature.add(String.valueOf(frame.getLineno()));

                    break;
                }
            }
        }

        return signature.toString();
    }
}
