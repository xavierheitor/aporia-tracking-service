# Aporia Tracking Service

Serviço de rastreamento GPS em Java 25 com Maven e PostgreSQL 16/PostGIS. Dois processos usam o mesmo JAR e rodam em contêineres separados.

| Processo | Responsabilidade |
| --- | --- |
| Gateway TCP | Recebe conexões Suntech e Teltonika na porta 5500, identifica equipamentos, mantém as sessões e persiste pacotes brutos. O dispatcher opcional envia comandos pelo socket do equipamento. |
| Worker | Lê os pacotes persistidos, decodifica mensagens e grava posições, respostas e eventos no banco. |

A API HTTP está prevista para uma etapa posterior.

## Por onde começar

| Se você quer… | Comece por |
| --- | --- |
| Entender o projeto e executar o primeiro teste com um rastreador simulado | [Primeiros passos](docs/primeiros-passos.md) |
| Acompanhar uma conexão, identificar qual thread trabalha nela e seguir uma posição até o banco | [Arquitetura e fluxos](docs/arquitetura.md) |
| Configurar, descobrir e autorizar um equipamento físico | [Gateway TCP e protocolos](docs/gateway-tcp.md) |
| Entender o envio, a recuperação e o significado dos estados de comandos | [Do comando ao rastreador](docs/arquitetura.md#do-comando-ao-rastreador) |
| Preparar o ambiente, aplicar migrations, subir os processos e verificar a versão implantada | [Deploy e operação](docs/deploy.md) |

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
| [compose.database.yaml](compose.database.yaml) | Um PostgreSQL 16 com PostGIS, volume persistente e porta local 55434. O mesmo arquivo também cria uma instância isolada para testes usando outro projeto Compose. |
| [compose.services.yaml](compose.services.yaml) | Gateway e worker na rede do banco. O dispatcher é opcional dentro do gateway. |

O serviço de banco responde pelo nome `postgres` na rede `aporia-tracking-db_default`. Configure a senha em `.env`, suba o banco, aplique as migrations e inicie os serviços com a imagem da revisão desejada. As instruções para uma instalação nova ou para migrar dados existentes estão no [guia de deploy](docs/deploy.md).

Para configurar um rastreador, listar pendências e autorizar um equipamento em um tenant, consulte o [guia do gateway TCP](docs/gateway-tcp.md). Para construir a imagem, habilitar o envio da consulta de firmware e acompanhar o emissor, consulte o [guia de deploy](docs/deploy.md).

## Leitura das migrations

O Flyway aplica os arquivos por versão e confere o checksum de cada migration já executada. Esta sequência acompanha o caminho do equipamento e dos dados pelo serviço.

| Versão | Efeito no fluxo |
| --- | --- |
| V1 | Cria tenants, dispositivos e a fila de pacotes brutos que liga gateway e worker. |
| V2 | Associa dispositivos a ativos por vigência e persiste mensagens, histórico espacial, última posição e eventos da outbox. |
| V3 | Introduz a fila de comandos e o histórico das respostas recebidas. |
| V4 | Registra o instante da reserva e introduz o resultado de entrega incerta. |
| V5 | Atribui um token à reserva para que um emissor antigo perca a autorização após recuperação. |
| V6 | Distingue a reserva do início da transmissão e registra quando esta começou. |
| V7 | Identifica o proprietário da transmissão, limita os comandos em voo por dispositivo e reforça o escopo das referências de respostas. |
| V8 | Registra solicitante e chave de requisição para reconhecer repetições da mesma solicitação. |
| V9 | Separa descoberta de cadastro e promove o equipamento ao tenant escolhido na aprovação administrativa. |
| V10 | Identifica a sessão TCP que pode reservar comandos para cada dispositivo entre réplicas do gateway. |

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
