# AGENTS.md — Aporia Tracking Service

> Instruções para agentes de programação que atuam neste repositório. Leia este arquivo antes de modificar código, banco de dados, testes ou infraestrutura.
>
> **Fonte de verdade:** o código, as migrations e os testes presentes no checkout atual prevalecem sobre este documento. As informações de progresso abaixo são derivadas do histórico de desenvolvimento e precisam ser verificadas no repositório.

## Atualização do checkout em 23/11/2026

O fluxo de homologação para consulta de firmware inclui dispatcher com recuperação por proprietário, gateway, worker e migrations V7–V8. A decisão de arquitetura, o contrato da fila, o ensaio de integração e o procedimento operacional estão em [docs/comandos.md](docs/comandos.md). O Compose de comandos é opt-in. O Marco C preserva RES/RPR sem vínculo nos formatos disponíveis. A API ficou para implementação posterior pelo mantenedor. A homologação física, a evidência para correlação positiva e a ativação em produção continuam pendentes. As seções de estado e roadmap abaixo registram o planejamento original.

## 1. Missão e escopo

O **Aporia Tracking Service** é um serviço backend Java para rastreamento de dispositivos GPS, com isolamento multi-tenant, comunicação TCP com rastreadores, ingestão e processamento de telemetria, persistência geoespacial e envio controlado de comandos. O protocolo implementado inicialmente é **Suntech**, com foco no ST8310R; a arquitetura deve permitir protocolos adicionais sem contaminar o núcleo de domínio com particularidades de um fabricante. Esse serviço servirá de base para uma api que fornece os dados e interface do serviço para clientes.

O objetivo imediato é fechar um **ciclo de comandos ponta a ponta, seguro e observável**:

1. Receber e validar a solicitação de comando.
2. Persistir o comando associado ao tenant e ao dispositivo corretos.
3. Reservar o comando sem duplicação concorrente.
4. Localizar a sessão TCP válida e transmitir o payload com controle de concorrência.
5. Persistir com precisão o resultado conhecido da tentativa, sem confundir escrita local com execução no dispositivo.
6. Receber `RES`/`RPR` e correlacionar a resposta ao comando **somente quando a correlação for inequívoca**.
7. Expor criação, consulta de status e respostas por API autenticada.
8. Demonstrar o fluxo completo em testes de integração e, posteriormente, com equipamento real.

Não iniciar um painel web, api, novos protocolos ou funcionalidades comerciais antes de concluir esse fluxo, salvo prioridade explícita do responsável pelo projeto.

## 2. Tecnologias e organização

- **Java 25**, **Maven**, testes com **JUnit 5**.
- **PostgreSQL 16** com **PostGIS**; acesso JDBC e pool HikariCP onde configurado.
- **Flyway** para evolução de schema; migrations conhecidas V1–V6.
- **TCP sockets** e sessões por dispositivo; worker de ingestão/processamento.
- Ambiente de testes: contêiner Docker `aporia-tracking-postgis-test`, banco `aporia_tracking_test`, porta anteriormente utilizada `55433`.
- Variáveis de teste: `TRACKING_TEST_DB_URL`, `TRACKING_TEST_DB_USER`, `TRACKING_TEST_DB_PASSWORD`.
- Pacotes conhecidos:
  - `software.aporia.tracking.gateway`: `DeviceSession`, `SessionRegistry`, `TcpClientHandler`.
  - `software.aporia.tracking.protocol`: reconhecimento, decoder, roteamento e validação Suntech.
  - `software.aporia.tracking.worker`: `TrackingWorker`.
  - `software.aporia.tracking.storage`: repositórios JDBC e ingestão.
  - `software.aporia.tracking.core`: entidades e máquina de estados.
  - `software.aporia.tracking.command.dispatcher`: `CommandTransport`, `SessionCommandTransport` e futuro `CommandDispatcher`.
  - `software.aporia.tracking.support` em testes: `TestDatabase`, `TestFixtures`.

**Respeitar a organização atual escolhida pelo mantenedor.** 

## 3. Estado conhecido — confirmar no checkout

### Implementado e exercitado em testes relatados

- Gateway TCP Suntech, identificação do dispositivo e registro/substituição de sessões.
- `DeviceSession`: envio de bytes sob `ReentrantLock`, invalidação de sessão e checagem de conectividade.
- `SessionRegistry`: registro, consulta, substituição e remoção condicional de sessões.
- Ingestão de pacotes brutos, validação de dispositivo, hash e processamento transacional.
- Decoder Suntech para telemetria `STT`/`ASTT`, heartbeat `ALV`, e roteamento de mensagens com capacidades múltiplas.
- Worker capaz de registrar respostas `RES`/`RPR` e processar posição embutida em `RES;STT` sem perder a resposta.
- Persistência de mensagens, posições, última posição e eventos de outbox.
- `device_commands` e `device_command_responses` criadas nas migrations.
- `DeviceCommandRepository`: enfileiramento e consulta de status por tenant.
- `DeviceCommandStatus`: `queued`, `sending`, `transmitting`, `sent`, `responded`, `timeout`, `delivery_failed`, `delivery_unknown`, `cancelled`.
- `DeviceCommandDispatchRepository`: `claimNext`, `claimNextForDevice`, `authorizeTransmission` e `markSent`, com `claim_token`.
- `DeviceCommandRecoveryRepository`: expiração de fila e recuperação de reservas/transmissões antigas para `delivery_unknown`.
- `CommandTransport` e `SessionCommandTransport`: abstração de transporte e integração com `SessionRegistry`.
- Testes JDBC de reserva, concorrência, rollback, revogação de token e recuperação; testes TCP locais do transporte.

### Ainda NÃO considerar concluído

- **`CommandDispatcher` de produção:** a última versão proposta foi explicitamente considerada inadequada para produção. Verificar se existe arquivo local; **não presumir que está pronto**.
- **Envio real integrado ao banco:** ainda não foi demonstrado, no histórico, um teste único que percorra `device_commands → dispatcher → socket → status`.
- **Correlação segura de `RES`/`RPR` com `device_commands`:** a resposta é armazenada, mas o vínculo `command_id` ainda não está implementado de forma confiável.
- **API HTTP autenticada** para comandos, dispositivos, posições e histórico.
- **Testes ponta a ponta com dispositivo físico** e operação/deploy em produção.
- **Auditoria completa** das migrations, constraints, índices, transações, segurança, logs e cobertura de testes.

## 4. Princípios obrigatórios de engenharia

1. **Código de produção, não protótipos descartáveis.** Evitar implementações sabidamente inseguras com promessa de correção futura.
2. **Simplicidade justificada.** Não criar estados, interfaces, migrations ou camadas sem um problema concreto e teste associado.
3. **Multi-tenancy por construção.** Toda leitura/mutação sensível deve restringir `tenant_id`; verificar também `device_id` e protocolo quando aplicável. Testar acesso cruzado.
4. **Integridade transacional.** Reservas e transições de estado devem ser condicionais, atômicas e idempotentes quando cabível; nunca assumir que `SELECT` seguido de `UPDATE` é seguro sem proteção.
5. **Sem reenvio automático quando o resultado for incerto.** `IOException`, timeout, desconexão ou falha de processo podem ocorrer depois de transmissão parcial.
6. **`sent` significa apenas escrita local concluída**, não entrega, recebimento ou execução pelo rastreador. A confirmação de aplicação exige resposta de protocolo correlacionada.
7. **Nenhuma correlação por palpite.** Não associar a resposta ao “último comando” se houver ambiguidade; avaliar serialização por dispositivo e identificadores reais do protocolo.
8. **Comandos operacionais sensíveis** (por exemplo, imobilização) exigem autorização, validação específica, auditoria e política de execução; não habilitar por padrão.
9. **Não registrar payloads sensíveis, credenciais, tokens de acesso ou dados pessoais em logs.** Preferir IDs de correlação, tenant, device, comando, estado e código de erro.
10. **Não alterar migrations já aplicadas.** Criar migration incremental somente quando necessária, após inspecionar o schema real. Nunca apagar dados para fazer um teste passar.
11. **Não modificar ambientes de desenvolvimento/produção ao testar.** Usar somente `aporia_tracking_test`; confirmar URL, usuário e banco antes de qualquer operação destrutiva.
12. **Falhas devem ser visíveis.** Não engolir exceções, não retornar sucesso antes do commit, e não reportar estado persistido quando apenas o resultado em memória foi alterado.
13. **JDBC e tempo:** domínio em `Instant`; para `TIMESTAMPTZ`, usar `OffsetDateTime` UTC no driver PostgreSQL quando necessário.
14. **Segurança do TCP:** validar sessão/identidade, considerar substituição concorrente, escrita parcial e fechamento do socket. `Socket.write()` não fornece confirmação da aplicação remota.
15. **Testes precisam deixar o banco limpo.** `TestFixtures.close()` exclui dependências antes de pais, incluindo `device_command_responses` e `device_commands`, dentro de transação. Usar identidades de dispositivo únicas por teste ou estratégia determinística segura.

## 5. Ponto crítico: autorização, recuperação e escrita TCP

O sistema tem `claim_token` e estados `sending`/`transmitting`. **Isso não elimina a janela de corrida entre autorizar no PostgreSQL e escrever no socket.** Um dispatcher pode ser suspenso, ter a reserva revogada por recovery e voltar a executar `send()`.

Antes de habilitar transmissão operacional:

- Definir o **ponto de linearização** do início da tentativa e quem pode revogar a autorização.
- Garantir que um dispatcher obsoleto não possa **iniciar** uma escrita após a revogação; impedir somente `markSent()` é insuficiente.
- Não manter transação PostgreSQL aberta durante operação TCP bloqueante.
- Projetar coordenação entre ciclo de vida da sessão, estado persistido e tentativa de envio, documentando limites inevitáveis.
- Garantir recuperação de `sending` e `transmitting` sem reenvio automático de tentativas ambíguas.
- Definir política de timeout de socket, concorrência por dispositivo, encerramento da sessão e resultado incerto.
- Considerar falha após a escrita TCP e antes de persistir `sent`.
- Validar que o dispatcher só usa payloads previamente autorizados/validados pelo protocolo; o repositório não deve virar API pública para comandos arbitrários.

**Não assumir “exactly once” com PostgreSQL + TCP.** Se o equipamento não fornecer identificadores e deduplicação, documentar a semântica real e escolher comportamento conservador.

## 6. Roadmap orientado a entregas

### Marco A — Auditoria curta do núcleo de comandos (PRIORIDADE IMEDIATA)

**Entregável:** decisão de arquitetura documentada e lista objetiva de correções, antes de escrever o dispatcher definitivo.

- [ ] Inspecionar `DeviceCommandDispatchRepository.java`, `DeviceCommandRecoveryRepository.java`, `DeviceCommandRepository.java`, `DeviceCommandStatus.java`, `CommandTransport.java`, `SessionCommandTransport.java`, `DeviceSession.java`, `SessionRegistry.java` e migrations V3–V6.
- [ ] Confirmar o schema e constraints efetivamente aplicados no banco de testes.
- [ ] Auditar condições de corrida entre recovery, claim, autorização, sessão e envio.
- [ ] Definir política de um comando em voo por dispositivo quando necessária para correlação.
- [ ] Definir contratos de resultado do dispatcher e persistência de falhas, sem estados falsos.
- [ ] Verificar cobertura de validação de comandos Suntech e formatos de `RES`/`RPR`.

**Pronto quando:** o fluxo e as invariantes estiverem claros e as decisões não exigirem refazer componentes a cada teste.

### Marco B — Dispatcher TCP funcional e seguro (PRIORIDADE 1)

- [ ] Implementar `CommandDispatcher` em `software.aporia.tracking.command.dispatcher` após a auditoria.
- [ ] Integrar `claimNextForDevice`, autorização, `CommandTransport`, `markSent` e tratamento persistente de resultado incerto.
- [ ] Manter comandos de dispositivos offline em `queued` até disponibilidade ou expiração; não reservá-los cegamente.
- [ ] Garantir exclusividade/serialização de envio por dispositivo conforme contrato de resposta do protocolo.
- [ ] Testar substituição/desconexão de sessão, escrita parcial, exceção, timeout, rollback, crash simulado, recuperação e concorrência.
- [ ] Criar teste de integração **PostgreSQL → dispatcher → socket local → estado `sent`**, além de cenário offline.

**Pronto quando:** o fluxo completo funciona e os cenários de falha não causam reenvio perigoso nem estados falsamente concluídos.

### Marco C — Correlação de respostas (PRIORIDADE 2)

- [ ] Inspecionar os formatos reais `RES`, `RPR`, `RES;STT` e a documentação/fixtures Suntech.
- [ ] Definir como identificar o comando respondido; se não for possível, impedir múltiplos comandos ambíguos em voo por dispositivo.
- [ ] Associar `device_command_responses.command_id` somente com evidência suficiente e escopo tenant/dispositivo/protocolo.
- [ ] Atualizar comando para `responded` e registrar `result` quando a resposta permitir distinguir sucesso/falha.
- [ ] Preservar a telemetria embutida em respostas compostas.
- [ ] Testar duplicatas, resposta tardia, resposta espontânea, respostas fora de ordem e ausência de correlação.

**Pronto quando:** comando e resposta aparecem vinculados corretamente ou explicitamente não correlacionados.

### Marco D — API mínima autenticada (PRIORIDADE 3)

- [ ] Definir contrato HTTP e autenticação/autorização multi-tenant.
- [ ] Criar comando validado para dispositivo permitido.
- [ ] Consultar comando, status, tentativa e respostas.
- [ ] Consultar dispositivos, posição atual e histórico básico.
- [ ] Implementar validação de payload, limites, paginação, tratamento de erros, auditoria e testes de acesso cruzado.

**Pronto quando:** um cliente autenticado consegue comandar e consultar o ciclo de vida sem acesso direto ao banco.

### Marco E — End-to-end e operação (PRIORIDADE 4)

- [ ] Teste completo com gateway TCP, worker, dispatcher, PostgreSQL e API.
- [ ] Validar com simulador e depois equipamento físico Suntech, incluindo reconexão e falhas.
- [ ] Revisar migrations, índices, retenção, logs, métricas, health checks, alertas, backup e restauração.
- [ ] Definir deployment, configuração por ambiente, secrets, limites de recursos e procedimentos de rollback.
- [ ] Documentar comandos suportados e operações proibidas/condicionadas.

**Pronto quando:** houver evidência de comportamento previsível, segurança e observabilidade em ambiente representativo.

### Fora do escopo imediato

- Painel web e mapas do ORBI.
- Novos fabricantes/protocolos.
- Cercas eletrônicas, alertas avançados e relatórios comerciais.
- Escalabilidade distribuída avançada sem requisito medido.

## 7. Workflow exigido para agentes

Ao receber uma tarefa:

1. **Inspecionar antes de editar:** ler arquivos reais, testes, migrations e interfaces relacionadas. Não inventar construtores, nomes de colunas ou assinaturas.
2. **Definir entrega verificável:** descrever comportamento desejado, invariantes, riscos e testes de aceitação em poucas linhas.
3. **Fazer alteração coesa e mínima:** evitar reescritas desnecessárias, duplicação de lógica e abstrações especulativas.
4. **Adicionar testes relevantes:** caminho feliz, negação, isolamento tenant, concorrência, rollback e falha quando pertinentes.
5. **Executar validação:** compilação, testes direcionados, suíte unitária e integração afetada. Não declarar sucesso sem observar o resultado.
6. **Revisar segurança e operação:** estados persistidos, timeouts, logs, limpeza de fixtures, migrations e efeitos de reexecução.
7. **Relatar objetivamente:** arquivos modificados, decisão técnica, comandos executados, resultados, limitações e próximo item prioritário.

Evitar uma sequência interminável de “etapas” artificiais. Organizar o trabalho por **marcos e critérios de aceite**. Quando um problema novo surgir, explicar se ele bloqueia o marco atual ou se pode ficar no backlog.

## 8. Comandos de validação

```bash
# Compilação
mvn clean compile

# Testes unitários padrão
mvn clean test

# Integrações relevantes (Surefire pode não incluir *IT por padrão)
mvn -Dtest=DeviceCommandRepositoryIT,DeviceCommandDispatchRepositoryIT,DeviceCommandRecoveryRepositoryIT,TrackingWorkerMessageRoutingIT,TcpClientHandlerIT test

# Transporte TCP local
mvn -Dtest=SessionCommandTransportTest test

# Histórico de migrations — APENAS banco de testes
mvn flyway:info \
  -Dflyway.url="$TRACKING_TEST_DB_URL" \
  -Dflyway.user="$TRACKING_TEST_DB_USER" \
  -Dflyway.password="$TRACKING_TEST_DB_PASSWORD"
```

Antes de `flyway:migrate`, verificar explicitamente que a URL aponta para `aporia_tracking_test`. Nunca usar `flyway:clean` em ambiente compartilhado ou de produção.

## 9. Riscos conhecidos e dívida técnica a verificar

- `CommandDispatcher` proposto anteriormente não persiste imediatamente `delivery_unknown` em falha de escrita e possui janela de revogação antes de `send()`. **Não usar como implementação de produção.**
- `claimNext()` global pode reservar dispositivos offline; preferir processamento direcionado à sessão, com expiração de fila independente.
- `markSent()` registra apenas conclusão de escrita local.
- Recovery global pode afetar registros de outros testes em banco compartilhado; isolar testes ou serializar operações globais.
- `TestFixtures` com identidade fixa pode deixar colisões após falha; melhorar geração/limpeza segura.
- Testes de máquina de estados não garantem que todas as transições sejam respeitadas pelo banco: auditar SQL de atualização.
- Necessário verificar se o comando `sent` pode ficar indefinidamente sem resposta e como expiração e respostas tardias são reconciliadas.
- Necessário auditar a restrição de status criada na V4 e modificada na V6, bem como a integridade de `claim_token` e timestamps.
- Logs de warnings Maven/Flyway (`401` em metadata de repositório GitHub) devem ser investigados separadamente se impactarem builds reprodutíveis; não confundir com falha funcional.

## 10. Regra final

**Não otimizar para quantidade de classes, migrations ou testes aprovados. Otimizar para um serviço correto, seguro, operável e que entregue o fluxo de rastreamento e comandos de ponta a ponta.**

Se uma proposta não for segura para produção, explicitar o bloqueio antes de sugerir implementação. Se a informação necessária não estiver no checkout, pedir o arquivo ou evidência exata; não completar lacunas por suposição.
