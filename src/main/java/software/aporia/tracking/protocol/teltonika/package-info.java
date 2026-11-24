/**
 * Implementação binária Teltonika AVL para Codec 8 e Codec 8E.
 *
 * <p>A sessão recebe primeiro o comprimento e o IMEI do login. Em seguida,
 * remonta lotes AVL, valida sua estrutura e gera quadros para o gateway.
 * O ACK de login contém um byte; o ACK de dados contém quatro bytes com a
 * quantidade de registros. O adaptador apenas monta esses bytes: o gateway
 * decide quando enviá-los, após a persistência no caso dos lotes.</p>
 *
 * <p>O decodificador percorre os registros em ordem. As leituras incluem dados
 * GPS e a ignição quando reconhecida; outros valores de entradas e saídas (I/O)
 * são percorridos para validar a estrutura e continuam nos bytes brutos.</p>
 */
package software.aporia.tracking.protocol.teltonika;
