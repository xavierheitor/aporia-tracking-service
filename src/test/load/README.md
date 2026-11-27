# Simulador de carga

Ferramenta de teste de capacidade do Aporia Tracking Service. Ela não faz parte
do runtime: abre conexões TCP, envia telemetria sintética e compara o que foi
escrito com o que o gateway e o worker persistiram.

> Execute somente contra o banco de testes e as portas isoladas descritas
> abaixo. Nunca aponte o simulador para desenvolvimento ou produção.

## Objetivo

Simulador assíncrono multiprotocolo destinado à validação de capacidade,
estabilidade, latência e integridade da ingestão de telemetria.

Cada dispositivo simulado mantém uma conexão TCP própria. O relatório final
separa a duração programada do ramp-up e do tempo total, para que a taxa por
segundo não seja inflada pelo encerramento.

## Protocolos suportados

- Suntech STT.
- Teltonika AVL Codec 8.
- Teltonika AVL Codec 8 Extended.

Cada pacote simulado contém uma posição.

## Estrutura

- simulator.py: motor assíncrono e CLI.
- database.py: provisionamento e reconciliação.
- metrics.py: contadores, latências e percentis.
- protocols/: encoders dos protocolos.
- tests/: testes unitários.
- start.sh: inicialização do benchmark.
- stop.sh: restauração do desenvolvimento.

## Requisitos

- Python 3.9 ou superior.
- Java 25 e Maven.
- Docker e Docker Compose.
- PostgreSQL/PostGIS de testes.
- Imagem local da aplicação.
- Infraestrutura de observabilidade configurada.

## Instalação

Executar na raiz do repositório:

```bash
python3 -m venv runtime/load-testing/.venv

source runtime/load-testing/.venv/bin/activate

python -m pip install \
  -r src/test/load/requirements.txt
```

## Isolamento

O simulador utiliza exclusivamente:

- Banco: aporia_tracking_test.
- Schema: tracking_load.
- PostgreSQL local: 127.0.0.1:15437.
- Gateway local: 127.0.0.1:15500.
- Administrador: tracking_test_admin.

Gateway, worker e monitor utilizam roles
operacionais distintas, sem privilégios
administrativos.

O ambiente de desenvolvimento é interrompido
temporariamente durante os benchmarks.

O PostgreSQL de testes é conectado temporariamente
à rede backend com o alias postgres-load.

Nunca executar contra bancos de produção.

## Preparação administrativa

Criar o schema tracking_load no banco de testes.

Criar as roles:

- tracking_gateway
- tracking_worker
- tracking_monitor

As roles devem possuir LOGIN, sem privilégios
de superusuário, criação de banco, criação de
roles, replicação ou bypass de RLS.

Configurar credenciais exclusivas no arquivo:

runtime/load-testing/credentials.env

Variáveis obrigatórias:

- LOAD_GATEWAY_DB_PASSWORD
- LOAD_WORKER_DB_PASSWORD
- LOAD_MONITOR_DB_PASSWORD

Restringir as permissões do arquivo a 0600.
Nunca versionar credenciais.

Aplicar as migrations:

```bash
(
  export FLYWAY_URL="${TRACKING_TEST_DB_URL}?currentSchema=tracking_load,public"
  export FLYWAY_USER="$TRACKING_TEST_DB_USER"
  export FLYWAY_PASSWORD="$TRACKING_TEST_DB_PASSWORD"
  export FLYWAY_DEFAULT_SCHEMA=tracking_load

  mvn flyway:migrate
  mvn flyway:validate
)
```

Aplicar runtime-grants.sql e monitor-grants.sql,
disponíveis em docker/postgres, utilizando
search_path=tracking_load,public.

Confirmar previamente o banco e o usuário
administrativo de testes.

## Inicialização

```bash
src/test/load/start.sh
```

O script verifica a preparação do schema,
configura a rede e direciona os componentes
Java ao ambiente de benchmark.

O envio de exceções ao GlitchTip é desabilitado
durante os testes de carga.

## Testes unitários

```bash
PYTHONPATH=src/test/load \
  python -m unittest discover \
  -s src/test/load/tests -v
```

## Smoke test

```bash
python src/test/load/simulator.py \
  --devices 10 \
  --protocol mixed \
  --interval 5 \
  --duration 60 \
  --ramp-up 10 \
  --drain-timeout 60
```

## Benchmark

```bash
python src/test/load/simulator.py \
  --devices 100 \
  --protocol mixed \
  --interval 5 \
  --duration 600 \
  --ramp-up 60 \
  --drain-timeout 120
```

## Parâmetros

| Parâmetro | Descrição |
| --- | --- |
| --devices | Quantidade de dispositivos |
| --protocol | suntech, teltonika ou mixed |
| --interval | Intervalo de envio em segundos |
| --duration | Duração programada em segundos |
| --ramp-up | Distribuição inicial das conexões |
| --drain-timeout | Prazo de espera pelo processamento |

A duração programada inclui o ramp-up.

## Monitoramento

Acompanhar os dashboards do Grafana durante
a execução.

```bash
docker stats --no-stream
```

Observar:

- Conexões simultâneas.
- Throughput de ingestão e processamento.
- Latências p50, p95 e p99.
- CPU e memória dos componentes.
- Saturação do pool JDBC.
- Profundidade e idade das filas.
- Erros de protocolo e persistência.

A fila de outbox cresce enquanto não houver
um consumidor de eventos implementado.

## Relatórios

Os relatórios JSON são armazenados em:

runtime/load-testing/

Cada execução possui um tenant exclusivo.

O relatório distingue duração programada,
ramp-up, tempo total e tempo posterior ao
encerramento programado.

A taxa por segundo programado inclui o ramp-up.

Uma escrita TCP concluída não comprova
persistência no servidor.

O ACK Teltonika confirma a ingestão do pacote,
mas não o processamento posterior pelo worker.

A reconciliação agregada compara pacotes escritos,
pacotes persistidos e posições processadas.

Os registros permanecem no banco para inspeção.

## Limpeza

Após encerrar as transmissões e confirmar que
não há processamento pendente:

```bash
PYTHONPATH=src/test/load python -c \
'from database import cleanup; from uuid import UUID; cleanup(UUID("TENANT_UUID"))'
```

Substituir TENANT_UUID pelo identificador
registrado no relatório.

A limpeza é restrita ao tenant do benchmark.

## Restauração

```bash
src/test/load/stop.sh
```

O script desconecta o PostgreSQL de testes
da rede backend e restaura os componentes
do ambiente de desenvolvimento.

## Resultados iniciais

Data: 26/11/2026.

Ambiente de execução:

- macOS, 10 núcleos e 16 GiB de RAM.
- Docker com aproximadamente 3,81 GiB.
- Gateway: 1 vCPU e 512 MiB.
- Worker: 1 vCPU e 512 MiB.
- Monitor: 0,5 vCPU e 384 MiB.

| Indicador | Smoke test | Benchmark |
| --- | ---: | ---: |
| Dispositivos | 10 | 100 |
| Duração programada | 60 s | 600 s |
| Intervalo | 5 s | 5 s |
| Pacotes escritos | 115 | 11454 |
| Pacotes persistidos | 115 | 11454 |
| Posições processadas | 115 | 11454 |
| ACKs Teltonika | 57 | 5724 |
| ACK p50 | 4,75 ms | 3,19 ms |
| ACK p95 | 10,38 ms | 6,15 ms |
| ACK p99 | 20,68 ms | 13,37 ms |
| Falhas de transmissão | 0 | 0 |
| Divergência agregada | 0 | 0 |

O cenário de 100 dispositivos sustentou
aproximadamente 20 pacotes por segundo após
o ramp-up.

### Consumo observado

Amostra instantânea durante o benchmark
de 100 dispositivos:

| Componente | Memória | CPU |
| --- | ---: | ---: |
| Gateway | 231 MiB | 1,14% |
| Worker | 230,2 MiB | 0,67% |
| Monitor | 222,8 MiB | 8,90% |
| PostgreSQL | 86,86 MiB | 2,60% |

Total observado: aproximadamente 771 MiB,
sem incluir a infraestrutura de observabilidade.

Os valores não representam médias ou picos
de toda a execução.

### Consumo de telemetria

| Protocolo | Bytes por pacote |
| --- | ---: |
| Suntech | 70 |
| Teltonika Codec 8 | 47 |
| Teltonika Codec 8E | 56 |

Total persistido no benchmark de 100 dispositivos:
695859 bytes de payload.

O valor não inclui cabeçalhos TCP/IP,
retransmissões, logins ou ACKs.

### Armazenamento

As tabelas raw_packets, positions, messages
e outbox_events ocuparam aproximadamente
21,78 MiB após os dois benchmarks.

Estimativa preliminar de aproximadamente
1974 bytes por posição, incluindo índices.

Não utilizar essa estimativa como garantia
de dimensionamento em produção.

## Limitações

- Capacidade máxima ainda não determinada.
- Reconciliação automatizada agregada.
- Reconexões controladas não implementadas.
- Um registro AVL por pacote.
- Ausência de relatório CSV.
- Ausência de contabilização bidirecional de bytes.
- Percentis calculados sobre amostras em memória.
- Regime estável ainda não medido separadamente.
- Tráfego local sem latência móvel real.
- PostgreSQL sem limite individual de recursos.
- Homologação com equipamentos físicos pendente.

Os resultados documentam a capacidade observada
nos cenários executados, não uma certificação
de prontidão para produção.


## Documentação relacionada

- [README raiz](../../../README.md)
- [Observabilidade](../../../docs/observabilidade.md)
- [Métricas e painéis](../../../docs/metricas.md)
- [Índice da documentação](../../../docs/README.md)
- [Arquitetura](../../../docs/arquitetura.md)
- [Código do simulador](simulator.py)