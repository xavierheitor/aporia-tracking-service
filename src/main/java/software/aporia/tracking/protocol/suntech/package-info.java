/**
 * Implementação Suntech ASCII: mensagens textuais separadas por ponto e vírgula
 * e terminadas por CR (retorno de carro, {@code \r}).
 *
 * <p>{@link software.aporia.tracking.protocol.suntech.SuntechProtocol} junta os
 * fragmentos e extrai a identidade. O adaptador cria os quadros do gateway.
 * O decodificador interpreta os campos presentes no mapa de bits da mensagem.</p>
 *
 * <p>O classificador indica a categoria principal do cabeçalho; as capacidades
 * indicam todos os conteúdos presentes. Por exemplo, {@code RES;STT;...} contém
 * resposta e telemetria, por isso o roteador habilita ambos os processamentos.
 * Essa classificação não confirma a execução de um comando específico.</p>
 *
 * <p>O validador aceita a gramática de CMD/PRG. A operação que o dispatcher
 * permite enviar atualmente é delimitada por
 * {@link software.aporia.tracking.protocol.suntech.FirmwareVersionRequest}.</p>
 */
package software.aporia.tracking.protocol.suntech;
