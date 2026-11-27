# Aporia Tracking Service

Serviço de rastreamento GPS em Java 25, Maven, Spring Boot e PostgreSQL 16/PostGIS. Um mesmo JAR atende três perfis de processo, cada um em seu contêiner:

| Processo | Responsabilidade |
| --- | --- |
| Gateway TCP | Recebe conexões Suntech e Teltonika na porta 5500, identifica equipamentos, mantém as sessões e persiste pacotes brutos. O dispatcher opcional envia comandos pelo socket do equipamento. |
| Worker | Lê os pacotes persistidos, decodifica mensagens e grava posições, respostas e eventos no banco. |
| Monitor | Coleta snapshots periódicos das filas persistidas e os publica como gauges no Actuator, sem consultar o banco a cada scrape. |

A API HTTP de negócio está prevista para uma etapa posterior. Os perfis atuais não publicam API de negócio: o gateway escuta TCP, o worker consome a fila e o monitor apenas expõe métricas.

## Por onde começar

| Se você quer… | Comece por |
| --- | --- |
| Entender o projeto e executar o primeiro teste com um rastreador simulado | [Primeiros passos](docs/primeiros-passos.md) |
| Acompanhar uma conexão, identificar qual thread trabalha nela e seguir uma posição até o banco | [Arquitetura e fluxos](docs/arquitetura.md) |
| Configurar, descobrir e autorizar um equipamento físico | [Gateway TCP e protocolos](docs/gateway-tcp.md) |
| Entender o envio, a recuperação e o significado dos estados de comandos | [Do comando ao rastreador](docs/arquitetura.md#do-comando-ao-rastreador) |
| Preparar o ambiente, aplicar migrations, subir os processos e verificar a versão implantada | [Deploy e operação](docs/deploy.md) |
| Entender o schema, as constraints e o efeito de cada migration | [Migrations e schema](docs/migrations.md) |
| Entender health checks, logs, alertas e a infraestrutura de monitoramento | [Observabilidade](docs/observabilidade.md) |
| Consultar nomes de métricas, tags, semântica e PromQL | [Métricas e painéis](docs/metricas.md) |
| Executar o simulador de carga e ler os resultados | [Simulador e benchmarks](src/test/load/README.md) |
| Ver o mapa completo da documentação | [Índice da documentação](docs/README.md) |

## Organização do código

Os pacotes Java ficam sob `software.aporia.tracking`. Os testes seguem a mesma estrutura em `src/test/java`.

| Pacote | Responsabilidade |
| --- | --- |
| `core` | Tipos de pacote, quadro recebido e telemetria compartilhados pelo serviço. |
| `protocol` | Contratos de adaptador, sessão e decodificação, além dos registros usados para selecionar implementações. |
| `protocol.suntech` e `protocol.teltonika` | Formatos de mensagem, adaptadores e decodificadores de cada fabricante. A consulta de firmware fica com a implementação Suntech. |
| `gateway` | Listener TCP, conexões, registro de sessões ativas e escrita de ACKs. |
| `command` e `command.dispatcher` | Estados do comando, reserva, envio pela sessão e coordenação da recuperação. |
| `storage` | Repositórios JDBC de cadastro, filas, telemetria e monitoramento. |
| `storage.device` | Cadastro, descoberta e associação do equipamento ao ativo. |
| `storage.packet` | Ingestão, reserva e processamento de pacotes brutos. |
| `storage.command` | Fila de comandos, reserva, recuperação e persistência de respostas. |
| `storage.telemetry` | Mensagens processadas, posições, última posição e eventos da outbox. |
| `worker` | Consumo de pacotes, processamento transacional e política de retentativa. |
| `config` | Beans Spring dos perfis, segurança do Actuator e limites de configuração. |
| `observability` | Métricas Micrometer, health indicators, coleta de filas e filtragem de exceções. |

As interfaces ficam junto ao código que define o contrato. Por exemplo, o dispatcher define `CommandTransport` e o gateway fornece `SessionCommandTransport` usando suas sessões TCP. As exceções acompanham o fluxo que distingue a falha: o worker trata pacotes inválidos e falhas que admitem retentativa, enquanto a fila cheia é sinalizada por `DeviceCommandRepository.QueueFullException`. Repositórios que participam da mesma transação podem pertencer a pacotes diferentes e receber a mesma conexão JDBC.

## Documentação do código

A documentação faz parte do contrato do código e deve acompanhar qualquer alteração:

- Todo tipo, método e construtor tem Javadoc com propósito, `@param`, `@return` e `@throws` aplicáveis. Getters triviais recebem uma linha objetiva; métodos com transação, concorrência, protocolo ou SQL explicam a invariante que protegem.
- Comentários inline explicam o funcionamento em linguagem simples, mas sem repetir o óbvio: ordem de validação, máscaras de bits, cláusulas condicionais, locks, transações, timeouts e o motivo de uma asserção. O comentário responde "por que" e "o que acontece se falhar".
- Comentários e Javadoc são escritos em português do Brasil; nomes de tipos, métodos e colunas permanecem em inglês.
- Nenhum comentário, log ou exemplo deve conter payloads sensíveis, credenciais, tokens ou dados pessoais. Use tenant, dispositivo, comando e código de erro.
- O Javadoc não substitui testes. Invariantes de isolamento por tenant, idempotência, rollback, concorrência e semântica de `sent` continuam verificadas pela suíte.

Os pontos de entrada para leitura são `TrackingApplication`, `TcpGateway`, `TrackingWorker` e `CommandDispatcher`. As decisões de arquitetura estão em [docs/arquitetura.md](docs/arquitetura.md), o contrato de protocolo em [docs/gateway-tcp.md](docs/gateway-tcp.md) e a operação em [docs/deploy.md](docs/deploy.md). O mapa completo, com status e convenções, está no [índice da documentação](docs/README.md).

## Configuração e operação

O arquivo [.env.example](.env.example) mostra a configuração para execução no host. O Compose da aplicação define sua própria URL JDBC para a rede Docker. As migrations incrementais do Flyway ficam em [src/main/resources/db/migration](src/main/resources/db/migration).

| Arquivo | Uso |
| --- | --- |
| [compose.yaml](compose.yaml) | Banco e serviços da aplicação, separados por perfil Compose: `dev` (PostgreSQL de desenvolvimento em `127.0.0.1:5437`), `test` (banco isolado em `127.0.0.1:15437`) e `app` (gateway, worker e monitor). |
| [compose.observability.yaml](compose.observability.yaml) | Prometheus, Grafana, Alertmanager, Loki, Alloy e GlitchTip opcional. Usa a rede externa `aporia-observability`. |

O serviço de banco responde pelo nome `postgres` na rede `aporia-tracking-service_backend`. Configure as senhas em `.env`, suba o banco, aplique as migrations, provisione os logins de gateway, worker e monitor e inicie os serviços com a imagem da revisão desejada. As instruções para uma instalação nova ou para migrar dados existentes estão no [guia de deploy](docs/deploy.md).

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

O detalhamento de tabelas, colunas, constraints e índices de cada versão está em [Migrations e schema](docs/migrations.md). Não altere migrations já aplicadas, nem mesmo comentários: o Flyway valida o checksum dos arquivos. Crie uma versão incremental e confirme o schema real antes de escrever o SQL.

## Validação

Para compilar produção e testes com lint ou executar a suíte unitária:

```sh
mvn -Plint clean test-compile
mvn clean test
```

A política de análise Java está versionada em [`config/java/jdt.prefs`](config/java/jdt.prefs), e o VS Code Java a carrega por `java.settings.url` em `.vscode/settings.json`. As anotações reconhecidas são JSpecify. No Eclipse, importe as mesmas preferências de compilador para o projeto.

Produção e testes usam a mesma política. Permanecem habilitados os diagnósticos de acesso nulo, acesso potencialmente nulo, violação de contrato, conflitos de inferência e conversões de nulidade desconhecida para não nula. Uma anotação incompatível em uma implementação deve ser corrigida, inclusive em doubles de teste.

Duas categorias informativas ficam desabilitadas: `nonnullTypeVariableFromLegacyInvocation` (inferência de retorno não nulo em APIs genéricas sem anotações completas, como JDK/JDBC) e `annotatedTypeArgumentToUnannotated` (passagem de um tipo anotado para uma API ainda não anotada). Esses diagnósticos indicam limites da cobertura de anotações, sem demonstrar um defeito por si só. Isso não torna JDBC seguro contra `NULL`: os contratos do schema, a validação nas fronteiras e os testes de persistência continuam necessários. Reavalie essa escolha quando as bibliotecas fornecerem contratos completos.

Não adicionar comportamento de fallback, verificações redundantes ou supressões locais apenas para obter zero avisos. Marcar ausências válidas com `@Nullable`; usar `@NullMarked` somente onde o contrato for verdadeiro. Um painel sem avisos não certifica segurança, isolamento por tenant, concorrência ou integridade transacional.

As propriedades `tracking.*` consumidas por `@Value` e `@Scheduled` são documentadas em `src/main/resources/META-INF/spring-configuration-metadata.json`; mantenha esses metadados alinhados aos consumidores e aos perfis YAML. O projeto usa Flyway pelo plugin Maven e pelo suporte de testes, sem o módulo `spring-boot-flyway` de autoconfiguração: migrations não são executadas na partida dos serviços. A inclusão futura desse módulo exige revisar essa política explicitamente.

A IDE ignora apenas a sugestão de atualizar para uma versão minor mais nova do Spring Boot. Os alertas de versão patch, incompatibilidade e fim de suporte continuam com as severidades padrão da extensão.

O `javac` usado pelo Maven não executa a análise de nulidade do JDT; `mvn -Plint test` e a análise da IDE são verificações complementares. Para uma verificação em lote, use o compilador Eclipse com Java 25, `-properties config/java/jdt.prefs` e o classpath de testes do Maven, incluindo `src/main/java` e `src/test/java`.

A preparação de comandos legados, payloads inválidos e estados históricos fica em `src/test`, especialmente em `CommandTestFixtures`. Os testes de operações normais usam os contratos de produção: ingestão com tenant/dispositivo, solicitação auditada de firmware e reserva vinculada ao token de sessão. A persistência de produção não oferece versões menos restritivas para montar cenários de teste.

Para incluir as integrações JDBC e TCP, configure `TRACKING_TEST_DB_URL`, `TRACKING_TEST_DB_USER` e `TRACKING_TEST_DB_PASSWORD` para o banco `aporia_tracking_test`. A suíte cria e remove um schema isolado por execução. Os testes que iniciam as JVMs também criam e removem dois logins exclusivos para validar as permissões reais dos serviços. Use uma credencial de teste com permissão para criar roles.

```sh
mvn -Plint clean verify '-Dtest=*Test,*IT'
```

## Testes de carga

O simulador assíncrono cobre Suntech STT e Teltonika Codec 8/8E, mede latências e reconcilia pacotes escritos, persistidos e posições processadas. Ele usa exclusivamente o banco `aporia_tracking_test`, o schema `tracking_load` e portas locais isoladas; nunca execute os scripts contra desenvolvimento ou produção.

Instruções de preparação, execução, monitoramento e limpeza, além dos resultados observados, estão em [src/test/load/README.md](src/test/load/README.md).

## Observabilidade

Health checks, logs, GlitchTip, alertas e retenção estão em [Observabilidade](docs/observabilidade.md). O catálogo de métricas, a semântica e as consultas PromQL estão em [Métricas e painéis](docs/metricas.md). A infraestrutura correspondente sobe por [compose.observability.yaml](compose.observability.yaml).
