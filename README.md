# Aporia Tracking Service

Serviço de rastreamento GPS em Java 25 com Maven e PostgreSQL 16/PostGIS. Dois processos usam o mesmo JAR e rodam em contêineres separados.

| Processo | Responsabilidade |
| --- | --- |
| Gateway TCP | Recebe conexões Suntech e Teltonika na porta 5500, identifica equipamentos, mantém as sessões e persiste pacotes brutos. O dispatcher opcional envia comandos pelo socket do equipamento. |
| Worker | Lê os pacotes persistidos, decodifica mensagens e grava posições, respostas e eventos no banco. |

A API HTTP está prevista para uma etapa posterior.

## Organização do código

Os pacotes Java ficam sob `software.aporia.tracking`. Os testes seguem a mesma estrutura em `src/test/java`.

| Pacote | Responsabilidade |
| --- | --- |
| `core` | Tipos de pacote, quadro recebido e telemetria compartilhados pelo serviço. |
| `protocol` | Contratos de adaptador, sessão e decodificação, além dos registros usados para selecionar implementações. |
| `protocol.suntech` e `protocol.teltonika` | Formatos de mensagem, adaptadores e decodificadores de cada fabricante. A consulta de firmware fica com a implementação Suntech. |
| `gateway` | Listener TCP, conexões e registro de sessões ativas. |
| `command` e `command.dispatcher` | Estados do comando, reserva, envio pela sessão e coordenação da recuperação. |
| `storage` | Configuração do pool JDBC. |
| `storage.device` | Cadastro, descoberta e associação do equipamento ao ativo. |
| `storage.packet` | Ingestão, gravação e consulta de pacotes brutos. |
| `storage.command` | Fila de comandos, reserva, recuperação e persistência de respostas. |
| `storage.telemetry` | Mensagens processadas, posições, última posição e eventos da outbox. |
| `worker` | Consumo de pacotes, processamento transacional e política de retentativa. |

As interfaces ficam junto ao código que define o contrato. Por exemplo, o dispatcher define `CommandTransport` e o gateway fornece `SessionCommandTransport` usando suas sessões TCP. As exceções acompanham o fluxo que distingue a falha: o worker trata pacotes inválidos e falhas que admitem retentativa, enquanto a fila cheia é sinalizada por `DeviceCommandRepository.QueueFullException`. Repositórios que participam da mesma transação podem pertencer a pacotes diferentes e receber a mesma conexão JDBC.

## Configuração e operação

O arquivo `.env.example` mostra a configuração da aplicação para execução no host. O Compose da aplicação configura sua própria URL JDBC para acesso pela rede Docker. As migrations incrementais do Flyway ficam em [src/main/resources/db/migration](src/main/resources/db/migration).

| Arquivo | Uso |
| --- | --- |
| [compose.yaml](compose.yaml) | PostgreSQL/PostGIS de desenvolvimento, porta local 55434. |
| [compose.postgis-test.yaml](compose.postgis-test.yaml) | Instância PostgreSQL/PostGIS para testes, porta local 55433. |
| [compose.app.yaml](compose.app.yaml) | Gateway e worker. O gateway publica a porta 5500 em loopback. |
| [compose.commands.yaml](compose.commands.yaml) | Complemento de homologação que habilita o dispatcher no gateway e preserva o arquivo de propriedade em volume. |

Ao combinar os arquivos de banco e aplicação em um ambiente novo, confira o nome DNS do banco. A aplicação espera `postgres-postgis` na rede externa `aporia-tracking-service_default`, enquanto o serviço de desenvolvimento se chama `postgres`. A URL JDBC ou o alias de rede deve corresponder ao banco escolhido.

Para configurar um rastreador, listar pendências e autorizar um equipamento em um tenant, consulte o [guia do gateway TCP](docs/gateway-tcp.md). Para construir a imagem, habilitar o envio da consulta de firmware ou verificar uma tentativa de entrega, consulte o [guia de comandos](docs/comandos.md).

## Validação

Para compilar produção e testes com lint ou executar a suíte unitária:

```sh
mvn -Plint clean test-compile
mvn clean test
```

Para incluir as integrações JDBC e TCP, configure `TRACKING_TEST_DB_URL`, `TRACKING_TEST_DB_USER` e `TRACKING_TEST_DB_PASSWORD` para o banco `aporia_tracking_test`. A suíte cria e remove um schema isolado por execução.

```sh
mvn -Plint clean verify '-Dtest=*Test,*IT'
```
