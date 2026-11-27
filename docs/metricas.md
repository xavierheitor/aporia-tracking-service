# Métricas e painéis

> **Status:** contrato implementado. Nomes e tags são fixados por
> `MetricsContractTest`.

Este documento é o contrato operacional das métricas do serviço: nomes
Micrometer, tags permitidas, semântica dos contadores, consultas PromQL e
dashboards. A topologia, a inicialização, o health e os logs estão em
[Observabilidade](observabilidade.md).

## Instrumentação

A instrumentação usa Micrometer com exportação para Prometheus. As famílias
publicadas cobrem:

- Conexões TCP aceitas, encerradas, rejeitadas e ativas.
- Bytes recebidos, frames e pacotes por protocolo.
- Ingestão, falhas de persistência e latência de ingestão.
- Processamento, rejeição, retentativa e heartbeat do worker.
- Despacho, escrita TCP e recuperação de comandos.
- Profundidade e idade das filas de pacotes, comandos e outbox.
- Disponibilidade e idade do snapshot do monitor.
- Eventos de erro admitidos, suprimidos e descartados.
- JVM, GC, CPU, memória, HikariCP e pool JDBC.

## Nomes exportados

O código registra o nome Micrometer com pontos. O Prometheus converte pontos em
sublinhados e acrescenta sufixos conforme o tipo:

| Micrometer | Prometheus (exemplo) |
| --- | --- |
| `tracking.tcp.connections.accepted` (Counter) | `tracking_tcp_connections_accepted_total` |
| `tracking.devices.connected` (Gauge) | `tracking_devices_connected` |
| `tracking.packet.ingestion` (Timer) | `tracking_packet_ingestion_seconds_*` |
| `tracking.queue.packets.oldest.age` (Gauge, segundos) | `tracking_queue_packets_oldest_age_seconds` |

Confirme o nome efetivo em `/actuator/prometheus` antes de criar painel ou
alerta. O código é a fonte de verdade; o catálogo abaixo é derivado dele.

## Cardinalidade

Use somente dimensões de cardinalidade limitada, como `protocol`, `result`,
`status` e `component`.

Nunca use IMEI, `tenant_id`, `device_id`, `packet_id`, `command_id`, IP, payload,
mensagem de exceção ou qualquer valor livre como label. IDs operacionais podem
aparecer em logs estruturados, mas não em séries Prometheus.

## Semântica

- Contadores de persistência são incrementados **após o commit**. Um rollback não
  conta como sucesso.
- Contadores de escrita TCP não representam confirmação de recebimento nem de
  execução pelo rastreador.
- O monitor preserva o último snapshot válido quando uma coleta falha e expõe a
  idade da amostra.
- Uma falha de coleta não deve ser interpretada como fila vazia.
- Contadores Prometheus são estimativas operacionais, não contabilidade de
  faturamento nem histórico individual. O histórico fica no PostgreSQL.

## Catálogo de métricas

| Métrica Micrometer | Tipo | Quem publica | Semântica |
| --- | --- | --- | --- |
| `tracking.tcp.connections.accepted` | Counter | Gateway | Sockets aceitos pelo listener. |
| `tracking.tcp.connections.closed` | Counter | Gateway | Sockets encerrados. |
| `tracking.tcp.connections.rejected` | Counter | Gateway | Conexões recusadas antes da sessão. |
| `tracking.tcp.received` | Counter (bytes) | Gateway | Bytes lidos dos sockets, inclusive fragmentos inválidos. |
| `tracking.tcp.connections.active` | Gauge | Gateway | Sockets aceitos ainda abertos. |
| `tracking.devices.connected` | Gauge | Gateway | Sessões de dispositivo registradas nesta instância. |
| `tracking.protocol.errors` | Counter | Gateway | Falhas de protocolo ou de sessão. |
| `tracking.protocol.frames.received` | Counter (tag `protocol`) | Gateway | Quadros completos entregues pelo adaptador. |
| `tracking.packets.received` | Counter (tag `protocol`) | Gateway | Pacotes prontos para ingestão. |
| `tracking.packets.ingested` | Counter (tag `protocol`) | Gateway | Pacotes persistidos após commit. |
| `tracking.packets.ingestion.failed` | Counter (tag `protocol`) | Gateway | Falhas de ingestão. |
| `tracking.device.reconnections` | Counter (tag `protocol`) | Gateway | Reconexões que substituíram uma sessão anterior. |
| `tracking.packet.ingestion` | Timer (tag `protocol`) | Gateway | Duração da ingestão, com histograma de percentis. |
| `tracking.packets.processed` | Counter | Worker | Pacotes processados e confirmados após commit. |
| `tracking.packets.rejected` | Counter | Worker | Pacotes rejeitados em definitivo. |
| `tracking.packets.retry.scheduled` | Counter | Worker | Retentativas persistidas. |
| `tracking.worker.cycles.failed` | Counter | Worker | Ciclos que terminaram em falha. |
| `tracking.worker.cycles.empty` | Counter | Worker | Ciclos sem pacote elegível. |
| `tracking.packet.processing` | Timer | Worker | Duração do processamento confirmado. |
| `tracking.worker.heartbeat.age` | Gauge (segundos) | Worker | Tempo desde o início do último ciclo. |
| `tracking.command.dispatch` | Counter (tag `result`) | Gateway | Resultado de cada tentativa de despacho. |
| `tracking.command.dispatch.duration` | Timer (tag `result`) | Gateway | Duração do despacho por resultado. |
| `tracking.command.dispatch.errors` | Counter | Gateway | Falhas inesperadas de despacho. |
| `tracking.command.tcp.write` | Timer | Gateway | Duração da escrita local no socket. |
| `tracking.command.tcp.write.errors` | Counter | Gateway | Falhas de escrita TCP. |
| `tracking.command.recovery` | Counter | Gateway | Ciclos de recuperação concluídos. |
| `tracking.command.recovery.errors` | Counter | Gateway | Ciclos de recuperação que falharam. |
| `tracking.command.recovery.duration` | Timer | Gateway | Duração da recuperação. |
| `tracking.queue.collection.completed` | Counter | Monitor | Coletas concluídas. |
| `tracking.queue.collection.errors` | Counter | Monitor | Coletas que falharam. |
| `tracking.queue.collection.duration` | Timer | Monitor | Duração da consulta agregada. |
| `tracking.queue.packets.pending` | Gauge | Monitor | Pacotes pendentes. |
| `tracking.queue.packets.eligible` | Gauge | Monitor | Pendentes já vencidos e elegíveis. |
| `tracking.queue.packets.retry.waiting` | Gauge | Monitor | Pendentes aguardando `available_at`. |
| `tracking.queue.packets.oldest.age` | Gauge (segundos) | Monitor | Idade do pacote pendente mais antigo. |
| `tracking.queue.outbox.pending` | Gauge | Monitor | Eventos de outbox ainda não publicados. |
| `tracking.queue.outbox.oldest.age` | Gauge (segundos) | Monitor | Idade do evento pendente mais antigo. |
| `tracking.queue.commands` | Gauge (tag `status`) | Monitor | Comandos por estado persistido. |
| `tracking.queue.snapshot.age` | Gauge (segundos) | Monitor | Idade do último snapshot válido. |
| `tracking.queue.snapshot.available` | Gauge | Monitor | 1 se existe snapshot, 0 se nenhum. |
| `tracking.error.events.admitted` | Counter | Erros | Eventos de erro liberados para envio. |
| `tracking.error.events.suppressed` | Counter | Erros | Eventos suprimidos por deduplicação. |
| `tracking.error.events.discarded` | Counter | Erros | Eventos descartados pela sanitização. |

## Consultas PromQL

Confirme os nomes exportados antes de usar as consultas em alertas.

### Dispositivos conectados

```promql
sum(tracking_devices_connected)
```

### Ingestão por segundo

```promql
sum(rate(tracking_packets_ingested_total[5m]))
```

### Processamento por segundo

```promql
sum(rate(tracking_packets_processed_total[5m]))
```

### Latência p95 de ingestão

```promql
histogram_quantile(
  0.95,
  sum by (le) (
    rate(tracking_packet_ingestion_seconds_bucket[5m])
  )
)
```

### Tráfego recebido

```promql
sum(rate(tracking_tcp_received_bytes_total[5m]))
```

Os bytes recebidos representam dados lidos dos sockets, não o tráfego IP
faturado pela operadora.

### Filas e envelhecimento

```promql
sum(tracking_queue_packets_pending)
max(tracking_queue_packets_oldest_age_seconds)
sum by (status) (tracking_queue_commands)
sum(tracking_queue_outbox_pending)
```

### Comandos por resultado

```promql
sum by (result) (
  rate(tracking_command_dispatch_total[5m])
)
```

### Falhas de processamento

```promql
sum(rate(tracking_packets_rejected_total[5m]))
sum(rate(tracking_packets_retry_scheduled_total[5m]))
```

### Saúde da coleta

```promql
min(tracking_queue_snapshot_available)
max(tracking_queue_snapshot_age_seconds)
sum(rate(tracking_queue_collection_errors_total[5m]))
```

## Dashboards

Os dashboards são provisionados automaticamente a partir de
`docker/observability/grafana/dashboards`:

| Arquivo | Foco |
| --- | --- |
| `01-tracking-overview.json` | Visão geral de ingestão, processamento, filas e erros. |
| `02-tcp-gateway.json` | Conexões, bytes, frames, pacotes e protocolos. |
| `03-packet-processing.json` | Worker, rejeições, retentativas e latência. |
| `04-commands-and-queues.json` | Despacho, recuperação, respostas e filas. |
| `05-jvm-infrastructure.json` | JVM, GC, memória, CPU, HikariCP e pool JDBC. |

O provisionamento versionado é a fonte de verdade dos painéis. Ao criar um
painel manual, exporte o JSON e versione o arquivo.

## Alertas

As regras ficam em `docker/observability/prometheus/rules/tracking.yml` e são
enviadas ao Alertmanager. Limites iniciais devem ser ajustados após medir a
linha de base do ambiente. A ausência legítima de tráfego não deve ser tratada
como falha. Detalhes de canais e retenção estão em
[Observabilidade](observabilidade.md).

## Validação

- `MetricsContractTest` fixa nomes e tags visíveis; uma troca silenciosa de
  medidor quebra o teste antes de quebrar um painel.
- O endpoint `/actuator/prometheus` confirma o nome exportado.
- O catálogo é derivado de `src/main/java/software/aporia/tracking/observability`
  e de `GatewayConfiguration`/`WorkerConfiguration`.
- Ao adicionar uma métrica, atualize o catálogo, o teste de contrato e, se
  necessário, o dashboard e a regra de alerta correspondentes.

```sh
mvn -Plint -Dtest=MetricsContractTest test
```

## Documentos relacionados

- [Índice da documentação](README.md)
- [Observabilidade](observabilidade.md)
- [Arquitetura e fluxos](arquitetura.md)
- [Deploy e operação](deploy.md)
