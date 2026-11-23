# Primeiros passos

Quem chega ao projeto pode acompanhar um rastreador simulado até o banco antes de configurar um equipamento físico. Execute os comandos a partir da raiz do repositório.

## 1. Compilar e conhecer os processos

Use um JDK 25 e confira qual JDK o Maven realmente usa. Os testes unitários exercitam os decodificadores, as sessões e as regras de estado. Os testes TCP precisam poder abrir sockets em loopback.

```sh
java -version
mvn -version
mvn -Plint clean test
```

Abra primeiro o ponto de entrada do gateway e localize a chamada a `server.accept()`. Depois abra o ponto de entrada do worker e localize a chamada a `processOne()`. Cada classe inicia uma JVM diferente. Elas encontram o mesmo pacote por meio da tabela `raw_packets`. [TcpGateway.java](../src/main/java/software/aporia/tracking/gateway/TcpGateway.java) · [WorkerApplication.java](../src/main/java/software/aporia/tracking/worker/WorkerApplication.java)

## 2. Preparar o banco do primeiro teste

Se a instância `aporia-tracking-postgis-test` já estiver disponível na porta 55433, use-a. Para uma instalação local nova com Docker e Compose, construa a imagem PostgreSQL/PostGIS para a plataforma indicada pelo Compose de testes e suba apenas esse serviço.

```sh
docker build --platform linux/arm64 -f docker/postgres/Dockerfile -t aporia-postgres-postgis:16 .
docker compose -p aporia-tracking-tests -f compose.postgis-test.yaml up -d --wait
docker exec aporia-tracking-postgis-test psql -X -U tracking -d postgres -c '\l'
```

O Compose inicializa o banco administrativo `postgres`. Se `aporia_tracking_test` estiver ausente na lista, crie-o uma vez. Na instância dedicada a testes, disponibilize as extensões no schema público para que os schemas temporários possam reutilizá-las.

```sh
# Executar apenas quando o banco estiver ausente.
docker exec aporia-tracking-postgis-test createdb -U tracking aporia_tracking_test

docker exec aporia-tracking-postgis-test psql -X -v ON_ERROR_STOP=1 -U tracking -d aporia_tracking_test \
  -c 'SELECT current_database(), current_user' \
  -c 'CREATE EXTENSION IF NOT EXISTS postgis' \
  -c 'CREATE EXTENSION IF NOT EXISTS btree_gist'
```

Exporte as variáveis abaixo no terminal do Maven e informe em `TRACKING_TEST_DB_PASSWORD` a senha do usuário da instância de teste. Em Bash ou Zsh, o exemplo lê a senha sem gravá-la no histórico.

```sh
export TRACKING_TEST_DB_URL=jdbc:postgresql://localhost:55433/aporia_tracking_test
export TRACKING_TEST_DB_USER=tracking
printf 'Senha do banco de testes: '
IFS= read -rs TRACKING_TEST_DB_PASSWORD
printf '\n'
export TRACKING_TEST_DB_PASSWORD
```

## 3. Seguir um ciclo completo

```sh
mvn -Plint -Dtest=GatewayCommandCycleIT test
```

O teste cria um schema isolado, aplica as migrations e inicia gateway e worker como processos filhos em loopback. O rastreador simulado conecta na porta escolhida pelo sistema. O cenário verifica descoberta e aprovação Teltonika, confirmação de lotes AVL, persistência de posições e envio de consultas de firmware Suntech após reconexão. O critério de sucesso é o teste concluir com zero falhas e zero erros. O relatório fica em `target/surefire-reports/`. Na saída normal da JVM, o schema temporário é removido. [GatewayCommandCycleIT.java](../src/test/java/software/aporia/tracking/gateway/GatewayCommandCycleIT.java) · [TestDatabase.java](../src/test/java/software/aporia/tracking/support/TestDatabase.java)

Para rodar toda a suíte, incluindo testes de integração com sufixo `IT`, execute:

```sh
mvn -Plint clean verify '-Dtest=*Test,*IT'
```

## 4. Escolher o próximo arquivo

| Dúvida ao ler o código | Onde acompanhar |
| --- | --- |
| Como uma conexão desconhecida vira uma pendência? | Siga a seleção do adaptador, a consulta de cadastro e o desvio para descoberta em [TcpClientHandler.java](../src/main/java/software/aporia/tracking/gateway/TcpClientHandler.java). |
| Quando posso confirmar ao equipamento que recebi um lote? | Siga o commit em [JdbcPacketIngestion.java](../src/main/java/software/aporia/tracking/storage/packet/JdbcPacketIngestion.java) e o ACK que o handler escreve ao retornar. |
| Como os bytes se transformam na posição do veículo? | Siga a reserva, a decodificação e a associação temporal ao ativo em [TrackingWorker.java](../src/main/java/software/aporia/tracking/worker/TrackingWorker.java). |
| Quem escreve um comando no socket? | Acompanhe a reserva, a autorização e a sessão selecionada em [CommandDispatcher.java](../src/main/java/software/aporia/tracking/command/dispatcher/CommandDispatcher.java). |

Para entender os limites entre essas etapas, leia a [arquitetura](arquitetura.md). Antes de alterar o comportamento, consulte as [instruções de desenvolvimento](../AGENTS.md). Para manter os processos ativos fora do teste, siga o [guia de deploy](deploy.md).
