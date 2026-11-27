# Primeiros passos

Quem chega ao projeto pode acompanhar um rastreador simulado até o banco antes de configurar um equipamento físico. Execute os comandos a partir da raiz do repositório.

## 1. Compilar e conhecer os processos

Use um JDK 25 e confira qual JDK o Maven realmente usa. Os testes unitários exercitam os decodificadores, as sessões e as regras de estado. Os testes TCP precisam poder abrir sockets em loopback.

```sh
java -version
mvn -version
mvn -Plint clean test
```

Abra primeiro o ponto de entrada do gateway e localize a chamada a `server.accept()`. Depois abra o `WorkerLifecycle` e localize a chamada a `processOne()`. Cada perfil inicia uma JVM diferente. Elas encontram o mesmo pacote por meio da tabela `raw_packets`. [TcpGateway.java](../src/main/java/software/aporia/tracking/gateway/TcpGateway.java) · [WorkerLifecycle.java](../src/main/java/software/aporia/tracking/worker/WorkerLifecycle.java)

## 2. Preparar o banco do primeiro teste

Se já existe um banco dedicado a testes na porta 15437, use essa instância. Em uma instalação nova, reutilize o Compose do banco com outro nome de projeto; o volume e a rede ficam isolados do desenvolvimento. Configure em `.env` as credenciais da instância local. O exemplo usa o usuário `tracking_test_admin`.

```sh
testdb() {
  docker compose -p aporia-tracking-tests --profile test -f compose.yaml "$@"
}
testdb up -d --build --wait
testdb exec -T postgres-test psql -X -v ON_ERROR_STOP=1 \
  -U tracking_test_admin -d aporia_tracking_test \
  -c 'SELECT current_database(), current_user' \
  -c 'CREATE EXTENSION IF NOT EXISTS postgis' \
  -c 'CREATE EXTENSION IF NOT EXISTS btree_gist'
```

Em volume vazio, a imagem cria `aporia_tracking_test` no primeiro boot. Se um volume antigo já estiver inicializado, confirme a lista com `testdb exec -T postgres-test psql -X -U tracking_test_admin -d postgres -c '\l'`. Crie o banco com `testdb exec -T postgres-test createdb -U tracking_test_admin aporia_tracking_test` somente se ele estiver ausente.

Exporte as variáveis abaixo no terminal do Maven e informe em `TRACKING_TEST_DB_PASSWORD` a senha do usuário da instância de teste. Em Bash ou Zsh, o exemplo lê a senha sem gravá-la no histórico.

```sh
export TRACKING_TEST_DB_URL=jdbc:postgresql://localhost:15437/aporia_tracking_test
export TRACKING_TEST_DB_USER=tracking_test_admin
printf 'Senha do banco de testes: '
IFS= read -rs TRACKING_TEST_DB_PASSWORD
printf '\n'
export TRACKING_TEST_DB_PASSWORD
```

## 3. Seguir um ciclo completo

```sh
mvn -Plint -Dtest=GatewayCommandCycleIT test
```

O teste cria um schema isolado, aplica as migrations e provisiona dois logins temporários com os grants de deploy. A credencial da suíte precisa poder criar roles no banco dedicado de testes. O teste inicia gateway e worker como processos filhos em loopback. O rastreador simulado conecta na porta escolhida pelo sistema. O cenário verifica descoberta e aprovação Teltonika, confirmação de lotes AVL, persistência de posições e envio de consultas de firmware Suntech após reconexão. O critério de sucesso é o teste concluir com zero falhas e zero erros. O relatório fica em `target/surefire-reports/`. Na saída normal da JVM, o schema temporário é removido. [GatewayCommandCycleIT.java](../src/test/java/software/aporia/tracking/gateway/GatewayCommandCycleIT.java) · [TestDatabase.java](../src/test/java/software/aporia/tracking/support/TestDatabase.java)

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

## Documentos relacionados

- [Índice da documentação](README.md)
- [Arquitetura e fluxos](arquitetura.md)
- [Gateway TCP e protocolos](gateway-tcp.md)
- [Deploy e operação](deploy.md)
- [Instruções de desenvolvimento](../AGENTS.md)
