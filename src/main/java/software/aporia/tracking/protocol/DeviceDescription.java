package software.aporia.tracking.protocol;

/**
 * Descreve a família de equipamento reconhecida em um quadro válido. O modelo pode estar ausente na mensagem.
 *
 * <p>Esses metadados ajudam na descoberta; não aprovam o equipamento nem
 * autenticam a identidade declarada por ele.</p>
 *
 * @param manufacturer fabricante associado ao formato reconhecido
 * @param deviceType categoria do equipamento
 * @param wireFormat formato observado na conexão
 * @param reportedModel código de modelo quando presente na mensagem
 */
public record DeviceDescription(String manufacturer, String deviceType, String wireFormat, String reportedModel) {}
