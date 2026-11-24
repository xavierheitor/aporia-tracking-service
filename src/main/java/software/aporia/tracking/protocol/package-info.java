/**
 * Contratos que separam o transporte TCP da interpretação dos protocolos.
 *
 * <p>O caminho de recepção tem duas fases:</p>
 * <ol>
 * <li>No gateway, {@link software.aporia.tracking.protocol.ProtocolRegistry}
 * escolhe um {@link software.aporia.tracking.protocol.ProtocolAdapter}.
 * Cada conexão recebe uma {@link software.aporia.tracking.protocol.ProtocolSession},
 * que junta fragmentos TCP e emite {@link software.aporia.tracking.protocol.ProtocolFrame}
 * completos. Uma leitura TCP pode conter parte de uma mensagem ou várias mensagens.</li>
 * <li>Depois da persistência dos bytes, o worker consulta o
 * {@link software.aporia.tracking.protocol.DecoderRegistry} pela versão gravada
 * no pacote. O {@link software.aporia.tracking.protocol.TelemetryDecoder} produz
 * um {@link software.aporia.tracking.protocol.DecodedPacket} com leituras e eventual
 * tipo de resposta. Reconhecer uma resposta não a correlaciona a um comando enviado.</li>
 * </ol>
 *
 * <p>Na saída, {@link software.aporia.tracking.protocol.CommandValidatorRegistry}
 * seleciona a validação sintática do comando. A autorização da operação e do tenant
 * permanece com os componentes que solicitam e despacham comandos.</p>
 */
package software.aporia.tracking.protocol;
