# Gateway TCP Suntech e Teltonika

O modo padrão `TRACKING_PROTOCOL=auto` atende os dois fabricantes na mesma porta (5500 por padrão). Cada conexão seleciona um adaptador pelo prefixo do primeiro frame e conserva sua própria sessão. Os valores `suntech` e `teltonika` restringem o listener a um fabricante. O timeout inicial de leitura é de 30 segundos. Após identificar um dispositivo habilitado, o timeout de inatividade passa a `TRACKING_TCP_SESSION_IDLE_SECONDS`, com padrão de 180 segundos.

## Teltonika FMB900

1. Cadastrar o equipamento habilitado no tenant com protocolo `teltonika` e identidade igual ao IMEI completo de 15 dígitos. Associar o dispositivo ao ativo com vigência que cubra a hora das leituras, inclusive as armazenadas pelo rastreador enquanto offline.
2. No Teltonika Configurator, configurar APN em GPRS (2001, usuário e senha em 2002/2003 quando exigidos pela operadora). Configurar o endereço alcançável do gateway em 2004, sua porta TCP em 2005 e o transporte TCP em 2006 (valor 0). A publicação padrão do Compose está em loopback. Para equipamento físico, disponibilizar o listener pela rede de homologação. Referência: QM-FMB900, seção Quick SMS configuration.
3. Em System, selecionar Codec 8 ou Codec 8 Extended. Em GPRS, selecionar ACK Type AVL quando disponível. O listener recebe TCP simples. O ping de rede FF é aceito entre lotes. Dimensionar o timeout de inatividade acima do intervalo de envio ou de ping desejado. [Configuração de sistema](https://wiki.teltonika-gps.com/view/FMB900_System_settings) e [configuração GPRS](https://wiki.teltonika-gps.com/view/FMB900_GPRS_settings).

A sessão confirma o IMEI cadastrado com um byte 01 e recusa um IMEI desconhecido ou desabilitado com 00. Para cada lote, valida preâmbulo, tamanho, codec, contagens de registros e I/O e CRC-16/IBM. O ACK de quatro bytes informa a quantidade de registros somente após o commit do pacote bruto. A confirmação cobre a ingestão. O worker processa o lote em outra transação e preserva o índice original de cada leitura. A retransmissão do mesmo pacote bruto preserva o histórico de recepção e evita duplicar posições e eventos. [Protocolo AVL sobre TCP](https://wiki.teltonika-gps.com/view/Teltonika_Data_Sending_Protocols).

O worker extrai coordenadas, horário UTC, velocidade, rumo, satélites e ignição quando o I/O 239 estiver presente. Leituras com zero satélites permanecem no pacote bruto e não atualizam a posição. Os demais I/Os, inclusive os campos de tamanho variável do Codec 8E, permanecem disponíveis nos bytes originais. A integração Teltonika cobre ingestão de telemetria. A política de comandos de saída habilita apenas a consulta Suntech de firmware.

## Suntech ST8310

O adaptador mantém os frames ASCII terminados por CR, a identidade de dez dígitos e os formatos STT, ASTT, ALV, RES e RPR. O decoder aceita o mapa estendido presente na resposta StatusReq da revisão 4 e preserva os campos adicionais no pacote bruto. A resposta da consulta de firmware nessa revisão ecoa a operação e o valor, sem identificar unicamente a tentativa. O histórico conserva a resposta sem associação automática ao comando. [Ciclo de comandos](comandos.md).

## Validação

A suíte integra TcpGateway, worker, PostgreSQL/PostGIS e sockets locais. Testa os dois codecs na mesma porta usada por Suntech, recusa de IMEI, ping, fragmentação, CRC inválido, ACK de lote, posição com índice e retransmissão. Para executar, configurar as variáveis de conexão de aporia_tracking_test e usar `mvn clean package '-Dtest=*Test,*IT'`. A homologação física depende dos equipamentos e da rede de teste.
