
package software.aporia.tracking.worker;

/**
 * Sinaliza uma condição de processamento que permite desfazer os efeitos parciais e reagendar o
 * pacote.
 *
 * <p>É uma exceção não verificada que carrega apenas um código estável; a quantidade de tentativas e o atraso
 * são decididos por {@link RetryPolicy}. O fluxo atual do {@link TrackingWorker} não a lança: a retentativa é
 * decidida pelo retorno de {@code saveTelemetry} e pelo código {@code ASSIGNMENT_NOT_FOUND}. A classe
 * permanece como contrato para extensões do processamento.</p>
 */
public class RetryableProcessingException
        extends RuntimeException {

    /** Versão da forma serializada desta exceção. */
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** Motivo estável que acompanha a falha de processamento. */
    private final String errorCode;

    /**
     * Preserva o motivo usado no agendamento de nova tentativa.
     *
     * @param errorCode código que identifica o motivo da falha
     */
    public RetryableProcessingException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    /**
     * Expõe o motivo que acompanha o reagendamento do pacote.
     *
     * @return código estável do motivo registrado no pacote
     */
    public String getErrorCode() {
        return errorCode;
    }
}
