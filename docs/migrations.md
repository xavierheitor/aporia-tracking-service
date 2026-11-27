# Migrations e schema

As migrations ficam em `src/main/resources/db/migration` e são aplicadas pelo
Flyway. O nome do arquivo define a versão; o Flyway guarda o checksum de cada
arquivo já executado.

> **Não edite uma migration já aplicada.** Qualquer alteração de bytes, inclusive
> comentários, muda o checksum e faz `flyway:validate` falhar em bancos
> existentes. Para evoluir o schema, crie `V11__assunto.sql` (ou a próxima
> versão livre) e valide o SQL em uma instância isolada antes do deploy.

O código, os testes e as migrations são a fonte de verdade. Este documento
explica o efeito de cada versão no fluxo; em caso de divergência, consulte o SQL
real e os testes de integração.

## Sequência

| Versão | Tema | Efeito principal |
| --- | --- | --- |
| V1 | Cadastro e fila bruta | Cria `tenants`, `devices` e `raw_packets`, o elo entre gateway e worker. |
| V2 | Telemetria | Cria `assets`, `assignments`, `messages`, `positions`, `last_positions` e `outbox_events`; habilita PostGIS e `btree_gist`. |
| V3 | Comandos | Cria `device_commands` e `device_command_responses`, com escopo por tenant. |
| V4 | Entrega incerta | Adiciona `claimed_at` e `delivery_unknown_at`; inclui `delivery_unknown` no estado. |
| V5 | Reserva | Adiciona `claim_token` e exige token em `sending`. |
| V6 | Autorização de transmissão | Adiciona `transmitting` e `transmission_started_at`. |
| V7 | Propriedade | Adiciona `dispatch_owner`, limita comandos em voo e reforça FKs por tenant/protocolo. |
| V8 | Idempotência | Adiciona `request_key` e o índice único por solicitante, dispositivo e chave. |
| V9 | Descoberta | Cria `device_discoveries`, a view de pendências e a função de aprovação. |
| V10 | Sessão entre réplicas | Adiciona `devices.command_session_token`. |

## V1 — cadastro e pacotes brutos

Cria a base de multi-tenancy e a fila que liga os dois processos:

- `tenants`: locatário dono dos dispositivos e dos dados.
- `devices`: identidade do rastreador única por protocolo
  (`UNIQUE (protocol, identity)`), habilitado por padrão e com chave composta
  `(tenant_id, id)` para as FKs seguintes.
- `raw_packets`: bytes originais, `payload_hash`, `decoder_version`,
  `correlation_id`, estado (`pending`, `processed`, `rejected`), tentativas,
  `available_at` e instantes de recepção/processamento.
  `payload` é limitado a 1..65536 bytes e `attempts` não pode ser negativo.

Índices: `idx_devices_tenant`; fila parcial `idx_raw_packets_pending` por
`available_at, received_at, id`; histórico por dispositivo
`idx_raw_packets_device_received`.

## V2 — ativos, posições e outbox

Habilita `postgis` e `btree_gist` e cria a camada de telemetria:

- `assets`: bem rastreado, único por `(tenant_id, id)` e
  `(tenant_id, external_id)`.
- `assignments`: vigência `[starts_at, ends_at)` entre dispositivo e ativo.
  A exclusão GiST impede que o mesmo dispositivo pertença a dois ativos no mesmo
  instante; `ends_at` precisa ser posterior a `starts_at`.
- `messages`: uma linha por conteúdo único do dispositivo
  (`UNIQUE (tenant_id, device_id, payload_hash)`), ligada ao pacote cru.
- `positions`: histórico com geometria `Point, 4326`, índice de leitura e
  `UNIQUE (message_id, reading_index)`. Checks limitam coordenadas, velocidade,
  rumo (`0 <= heading < 360`), satélites e índice de leitura.
- `last_positions`: uma linha por ativo (`PK tenant_id, asset_id`), apontando
  para a posição mais recente.
- `outbox_events`: evento transacional com `payload JSONB`, `published_at` nulo
  enquanto pendente e contador de tentativas.

A ordem de escrita do worker é mensagem → posições → última posição → evento,
tudo na mesma transação. longitude vem antes de latitude no PostGIS.

## V3 — fila de comandos e respostas

`device_commands` guarda a saída para o rastreador:

- `protocol`, `payload` (1..8192 bytes), estado, resultado, tentativas,
  `available_at`, `sent_at`, `responded_at`, `expires_at` e `error_code`.
- Estados iniciais: `queued`, `sending`, `sent`, `responded`, `timeout`,
  `delivery_failed`, `cancelled`.
- `expires_at > created_at`; `responded` exige `responded_at`.

`device_command_responses` registra a resposta recebida, ligada ao pacote cru e,
quando houver correlação comprovada, ao comando (`command_id` pode ser nulo).
`response_type` aceita `RES` e `RPR`; o payload é limitado a 1..65536 bytes.
A resposta é única por pacote cru. As FKs incluem `tenant_id` para impedir
referência cruzada entre locatários.

Índices: fila por `available_at, created_at, id`; consulta por dispositivo e
estado; respostas por comando e por dispositivo.

## V4 — resultado incerto

Adiciona `claimed_at` (instante da reserva) e `delivery_unknown_at` (resultado
incerto). Recria a constraint de status incluindo `delivery_unknown`. Cria
índices para recuperar reservas vencidas em `sending` e para expirar comandos
ainda em `queued`.

`delivery_unknown` não autoriza reenvio automático: a escrita pode ter chegado
parcialmente ao equipamento.

## V5 — token da reserva

Adiciona `claim_token` à fila. O emissor que reserva recebe um UUID e precisa
reapresentá-lo nas transições condicionais. Se a recuperação revogar a reserva,
o token antigo deixa de casar e uma atualização tardia não sobrescreve a
tentativa nova. O check exige token em `sending`; o índice parcial acelera a
busca de reservas ativas.

## V6 — autorização de transmissão

Separa a reserva (`sending`) da autorização para escrever (`transmitting`).
Adiciona `transmission_started_at`, inclui o novo estado na constraint e exige
token em `sending` ou `transmitting`. O check
`chk_commands_transmission_started` exige o instante de início em
`transmitting`. A tentativa só escreve no socket depois da confirmação
transacional dessa transição.

## V7 — propriedade da transmissão

- `dispatch_owner` identifica a instância emissora que reservou a linha.
- O índice único parcial `uq_commands_in_flight` permite no máximo um comando
  por tenant e dispositivo em `sending`, `transmitting` ou `sent`.
- `idx_commands_sent_expiration` apoia o vencimento de comandos já escritos.
- Chaves compostas com `tenant_id`, `device_id` e `protocol` reforçam as FKs de
  resposta → comando e resposta → pacote cru.
- `requested_by` prepara a idempotência da V8.

## V8 — idempotência da solicitação

Adiciona `request_key`. O índice único parcial
`(tenant_id, device_id, requested_by, request_key)` devolve o comando existente
quando a mesma solicitação é repetida, em vez de enfileirar outro envio. Comandos
históricos podem manter `request_key` nulo.

## V9 — descoberta e aprovação

Cria `device_discoveries` para identidades que se conectaram mas ainda não
pertencem a um tenant:

- identidade única por protocolo (`UNIQUE (protocol, identity)`);
- fabricante, tipo e wire format observados;
- `first_seen_at`/`last_seen_at`, contador de observações;
- `approved_at` e `approved_by` gravados juntos (check de paridade).

Adiciona os mesmos metadados em `devices`. A view
`pending_device_discoveries` mostra apenas descobertas não aprovadas e sem
cadastro equivalente. A função `approve_device_discovery(uuid, uuid)`:

1. trava a descoberta com `FOR UPDATE`;
2. é idempotente para o mesmo tenant;
3. rejeita mudança silenciosa de tenant com `DISCOVERY_ALREADY_APPROVED`;
4. promove a descoberta a `devices` com o UUID original;
5. é `SECURITY INVOKER` e tem execução revogada de `PUBLIC`.

## V10 — sessão autorizada entre réplicas

Adiciona `devices.command_session_token`. Cada admissão de sessão grava um UUID
novo; o dispatcher compara o token da sessão local com o valor persistido antes
de reservar um comando. O banco passa a ser o árbitro entre réplicas do gateway,
sem depender de memória compartilhada. O token continua no cadastro após a
desconexão e, sozinho, não indica que o equipamento está online.

## Verificação

Com o banco de teste configurado:

```sh
export FLYWAY_URL="$TRACKING_TEST_DB_URL"
export FLYWAY_USER="$TRACKING_TEST_DB_USER"
export FLYWAY_PASSWORD="$TRACKING_TEST_DB_PASSWORD"
export FLYWAY_DEFAULT_SCHEMA=tracking_load
mvn flyway:info
mvn flyway:validate
```

Confirme banco, usuário e schema antes de aplicar. Nunca execute
`flyway:clean` em ambiente compartilhado. Os testes JDBC criam um schema
isolado por JVM; as migrations aplicadas por eles são removidas no fim.

## Documentos relacionados

- [Índice da documentação](README.md)
- [Arquitetura e fluxos](arquitetura.md)
- [Deploy e operação](deploy.md)
