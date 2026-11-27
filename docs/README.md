# Documentação

Este diretório reúne a documentação operacional e de engenharia do Aporia
Tracking Service. O código, as migrations e os testes do checkout são a fonte de
verdade; os textos explicam o comportamento, os limites e as decisões.

## Comece pelo objetivo

| Se você quer… | Leia | Conteúdo |
| --- | --- | --- |
| Entender o projeto e executar o primeiro ciclo com um rastreador simulado | [Primeiros passos](primeiros-passos.md) | Pré-requisitos, banco de teste, suíte e leitura guiada do código. |
| Entender componentes, threads, transações e fluxos | [Arquitetura e fluxos](arquitetura.md) | Gateway, worker, dispatcher, concorrência e caminho do pacote até a posição. |
| Configurar, descobrir e autorizar Suntech/Teltonika | [Gateway TCP e protocolos](gateway-tcp.md) | Detecção de protocolo, descoberta, aprovação, Teltonika FMB900 e Suntech ST8310. |
| Entender o schema e o efeito de cada migration | [Migrations e schema](migrations.md) | Tabelas, constraints, índices, funções e invariantes de V1 a V10. |
| Instalar, atualizar, operar e recuperar | [Deploy e operação](deploy.md) | Banco, rede, imagem, grants, migrations, capacidade, réplicas e backup. |
| Entender health, logs, alertas e a infraestrutura de monitoramento | [Observabilidade](observabilidade.md) | Topologia, portas, Actuator, logs, GlitchTip, retenção e segurança. |
| Consultar nomes, tags, semântica e PromQL | [Métricas e painéis](metricas.md) | Catálogo Micrometer, cardinalidade, semântica, consultas e dashboards. |
| Executar o simulador de carga | [Simulador de carga](../src/test/load/README.md) | Preparação, benchmark, reconciliação, limpeza e resultados. |
| Ver a visão geral e a política de código | [README do projeto](../README.md) | Processos, pacotes, validação, Javadoc e convenções de comentário. |

## Status dos documentos

| Documento | Status | Observação |
| --- | --- | --- |
| Primeiros passos | Implementado e validado localmente | Depende de banco de testes e das variáveis de ambiente. |
| Arquitetura e fluxos | Implementado | Descreve o código atual; não antecipa a API. |
| Gateway TCP e protocolos | Implementado | Homologação com equipamentos físicos pendente. |
| Migrations e schema | Implementado | Não altere migrations aplicadas; o Flyway valida o checksum. |
| Deploy e operação | Implementado e validado localmente | Não constitui certificação de produção. |
| Observabilidade | Implementado e validado localmente | Canais externos de alerta ainda são configurados no deploy. |
| Métricas e painéis | Implementado | Nomes e tags são fixados por teste de contrato. |
| Simulador de carga | Implementado e medido | Capacidade máxima e reconexões controladas ainda não determinadas. |

## Convenções

- **Um assunto por arquivo.** Cada página tem um objetivo e um público; o
  detalhe fica na página especializada, não duplicado no índice.
- **Nomes em minúsculas e kebab-case**, exceto `README.md`, reservado para
  índices e visão geral.
- **Português do Brasil** nos textos e comentários; identificadores de código,
  colunas e nomes de métricas permanecem em inglês.
- **Links relativos** entre páginas. Ao renomear ou mover um arquivo, atualize
  todas as referências em `README.md`, `docs/` e `src/test/load/README.md`.
- **Sem segredos, payloads, tokens ou dados pessoais.** Use tenant, dispositivo,
  comando, estado e código de erro em exemplos.
- **Migrations aplicadas são imutáveis.** O Flyway valida o checksum do arquivo
  inteiro; para evoluir o schema, crie a próxima versão.
- **Status explícito.** Use “implementado”, “validado localmente”, “pendente” ou
  “planejado”. Não confunda teste verde com prontidão de produção.
- **Fonte de verdade.** Quando texto e código divergirem, o código e os testes
  vencem; corrija o texto na mesma alteração.

## Manutenção

| Quando mudar… | Atualize também |
| --- | --- |
| Novo perfil, porta, serviço Compose ou variável de ambiente | [Deploy e operação](deploy.md) e [Observabilidade](observabilidade.md) |
| Novo protocolo, codec, descoberta ou comando | [Gateway TCP e protocolos](gateway-tcp.md), [Arquitetura](arquitetura.md) e Javadoc do pacote |
| Nova migration, tabela, constraint ou índice | [Migrations e schema](migrations.md) e a tabela de leitura no [README](../README.md) |
| Novo medidor, tag, dashboard ou alerta | [Métricas e painéis](metricas.md) e `MetricsContractTest` |
| Mudança de lifecycle, lock, transação ou retentativa | [Arquitetura](arquitetura.md) e Javadoc dos componentes afetados |
| Mudança no simulador, scripts ou resultados de benchmark | [Simulador de carga](../src/test/load/README.md) |
| Novo comando de validação ou pré-requisito | [Primeiros passos](primeiros-passos.md) e [README](../README.md#validação) |

## Escopo e pendências

O escopo atual cobre gateway, worker, monitor e persistência. A API HTTP de
negócio, a homologação com equipamentos físicos, a correlação positiva das
respostas Suntech e a preparação para produção continuam pendentes. As decisões
obrigatórias de engenharia e o workflow de alteração estão em
[AGENTS.md](../AGENTS.md).

## Validação

A documentação acompanha a suíte do projeto. Antes de publicar uma alteração,
execute os comandos da seção de validação do [README](../README.md#validação) e
confira os links relativos das páginas alteradas. O Javadoc é validado com
`-Xdoclint:all`; erros de referência ou HTML devem ser corrigidos, não
suprimidos.
