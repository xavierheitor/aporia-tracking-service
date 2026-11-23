# Gateway TCP Suntech e Teltonika

O modo padrão `TRACKING_PROTOCOL=auto` atende os dois fabricantes na mesma porta (5500 por padrão). Cada conexão seleciona um adaptador pelo prefixo do primeiro frame e conserva sua própria sessão. Os valores `suntech` e `teltonika` restringem o listener a um fabricante. O timeout inicial de leitura é de 30 segundos. Após identificar um dispositivo habilitado, o timeout de inatividade passa a `TRACKING_TCP_SESSION_IDLE_SECONDS`, com padrão de 180 segundos.

## Suporte por protocolo

| Protocolo | Recepção pelo gateway | Comandos enviados pelo dispatcher |
| --- | --- | --- |
| Suntech ASCII (linha ST8310) | Posições, presença e respostas `RES`/`RPR`, inclusive posição embutida | Consulta de versão de firmware |
| Teltonika AVL Codec 8 e 8E | Login, ping e lotes de telemetria com confirmação após a persistência | Nenhum comando implementado. Os ACKs fazem parte da recepção AVL. |

## Descoberta e autorização de equipamentos

Com a migration V9 aplicada, aponte o rastreador para o IP e a porta TCP do gateway. Uma mensagem Suntech reconhecida ou um lote AVL válido cria uma pendência administrativa. O formato da mensagem identifica a família de protocolo e a marca compatível. A identidade declarada pelo equipamento distingue reconexões dentro do mesmo protocolo. Um emissor que imita o protocolo também pode aparecer na fila, por isso o operador precisa conferir a identidade e o tenant antes de aprovar.

A lista mostra a identidade, a marca, o tipo, o último formato observado, o primeiro e o último contato e a contagem de observações. O campo de modelo guarda o código quando informado no mapa Suntech. Codec 8 e 8E permitem identificar o formato AVL, mas não distinguem um FMB900 de outros modelos compatíveis. Reconexões atualizam a mesma pendência. Enquanto aguarda aprovação, o gateway encerra a conexão após a descoberta, sem gravar telemetria nem disponibilizar sessão para comandos.

Com o cliente PostgreSQL instalado, configure a conexão administrativa do psql (por exemplo, via `PGSERVICE`) e execute na raiz do projeto:

```sh
scripts/equipamentos listar
scripts/equipamentos autorizar UUID_DA_DESCOBERTA UUID_DO_TENANT
```

A aprovação cria o dispositivo habilitado no tenant escolhido com os metadados observados e registra a hora e o usuário PostgreSQL da aprovação. A próxima conexão entra pelo fluxo normal. Repetir a aprovação para o mesmo tenant devolve o mesmo dispositivo. A fila é global e deve ficar restrita à administração da instalação. O proprietário da migration pode executar a função; para outro operador, conceda acesso à view, execução da função e as permissões nas tabelas exigidas por `SECURITY INVOKER`.

## Teltonika FMB900

1. Após configurar o envio e autorizar a descoberta no tenant, associar o dispositivo ao ativo com vigência que cubra a hora das leituras, inclusive as armazenadas pelo rastreador enquanto offline.
2. No Teltonika Configurator, configurar APN em GPRS (2001, usuário e senha em 2002/2003 quando exigidos pela operadora). Configurar o endereço alcançável do gateway em 2004, sua porta TCP em 2005 e o transporte TCP em 2006 (valor 0). A publicação padrão do Compose está em loopback. Para equipamento físico, disponibilizar o listener pela rede de homologação. Referência: QM-FMB900, seção Quick SMS configuration.
3. Em System, selecionar Codec 8 ou Codec 8 Extended. Em GPRS, selecionar ACK Type AVL quando disponível. O listener recebe TCP simples. O ping de rede FF é aceito entre lotes. Dimensionar o timeout de inatividade acima do intervalo de envio ou de ping desejado. [Configuração de sistema](https://wiki.teltonika-gps.com/view/FMB900_System_settings) e [configuração GPRS](https://wiki.teltonika-gps.com/view/FMB900_GPRS_settings).

O gateway responde 01 ao login de um IMEI habilitado ou desconhecido. Para o desconhecido, esse byte solicita o primeiro lote, cujo codec e CRC permitem registrar a descoberta. O lote de descoberta fica sem ACK de dados. Um IMEI desabilitado recebe 00. Para cada lote, valida preâmbulo, tamanho, codec, contagens de registros e I/O e CRC-16/IBM. O ACK de quatro bytes informa a quantidade de registros somente após o commit do pacote bruto. A confirmação cobre a ingestão. O worker processa o lote em outra transação e preserva o índice original de cada leitura. A retransmissão do mesmo pacote bruto preserva o histórico de recepção e evita duplicar posições e eventos. [Protocolo AVL sobre TCP](https://wiki.teltonika-gps.com/view/Teltonika_Data_Sending_Protocols).

O worker extrai coordenadas, horário UTC, velocidade, rumo, satélites e ignição quando o I/O 239 estiver presente. Leituras com zero satélites permanecem no pacote bruto e não atualizam a posição. Os demais I/Os, inclusive os campos de tamanho variável do Codec 8E, permanecem disponíveis nos bytes originais. A integração Teltonika cobre ingestão de telemetria.

## Suntech ST8310

O adaptador mantém os frames ASCII terminados por CR, a identidade de dez dígitos e os formatos STT, ASTT, ALV, RES e RPR. O decoder aceita o mapa estendido presente na resposta StatusReq da revisão 4 e preserva os campos adicionais no pacote bruto. A resposta da consulta de firmware nessa revisão ecoa a operação e o valor, sem identificar unicamente a tentativa. O histórico conserva a resposta sem associação automática ao comando. O worker conserva o horário de recepção e o conteúdo em `device_command_responses`, com `command_id` nulo e resultado `unknown`. [Fluxo do dispatcher](arquitetura.md#do-comando-ao-rastreador).

Na homologação física do ST8310R, confira a versão conhecida do equipamento, solicite a consulta com uma chave inédita e compare a captura do socket com o estado persistido e a resposta recebida. Repita com desconexão e resposta tardia. Faça o ensaio em rede de acesso controlado: a identidade declarada no protocolo não autentica criptograficamente o aparelho.

## Validação

A suíte integra TcpGateway, worker, PostgreSQL/PostGIS e sockets locais. Testa os dois codecs na mesma porta usada por Suntech, descoberta, aprovação, recusa de IMEI desabilitado, ping, fragmentação, CRC inválido, ACK de lote, posição com índice e retransmissão. A homologação física depende dos equipamentos e da rede de teste. Consulte o [README para executar a suíte](../README.md#validação).
