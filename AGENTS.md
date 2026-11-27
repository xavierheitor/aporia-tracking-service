# Instruções de desenvolvimento

Leia este arquivo antes de modificar código, banco, testes ou infraestrutura. O código, as migrations e os testes do checkout são a referência para o comportamento atual. Preserve a organização dos pacotes por responsabilidade.

O escopo atual cobre gateway, worker e persistência. A API será implementada pelo mantenedor. Novos protocolos, painel web e funcionalidades comerciais dependem de prioridade explícita. A homologação com equipamentos físicos, a correlação positiva das respostas Suntech e a preparação para produção continuam pendentes.

Consulte o [README](README.md) para arquitetura e validação, o [guia do gateway](docs/gateway-tcp.md) para descoberta e autorização e o [guia de deploy](docs/deploy.md) para operação e recuperação.

## Princípios obrigatórios de engenharia

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

## Código de produção e isolamento dos testes

- Não introduzir construtores, sobrecargas, métodos, flags ou caminhos de execução exclusivamente para suportar testes. Sobrecargas continuam válidas quando possuem finalidade real em produção.
- Dependências de produção devem ser explícitas e injetadas.
- Os testes são responsáveis por configurar suas dependências, fixtures, mocks e infraestrutura.
- Não introduzir dependências implícitas de fallback para preservar compatibilidade com testes existentes.
- Quando um contrato de produção mudar, atualizar os testes correspondentes.
- Infraestrutura específica de testes deve permanecer em `src/test`.
- O comportamento de produção não deve depender de a aplicação estar executando em ambiente de teste.

## Alterações no dispatcher

Preserve o contrato descrito no [fluxo do dispatcher](docs/arquitetura.md#do-comando-ao-rastreador). A transição confirmada para `transmitting` inicia a tentativa de envio. Uma reserva em `sending` pode expirar. A recuperação de uma transmissão com proprietário exige exclusividade sobre o arquivo do emissor. O tempo decorrido, sozinho, é insuficiente para revogar uma tentativa em curso.

Ao alterar esse fluxo, exercite concorrência, substituição da sessão, timeout de escrita e falha entre a escrita e o commit do resultado. Mantenha a operação TCP fora da transação PostgreSQL e valide operação, identidade e sessão antes do envio.

## Workflow exigido para agentes

Ao receber uma tarefa:

1. **Inspecionar antes de editar:** ler arquivos reais, testes, migrations e interfaces relacionadas. Não inventar construtores, nomes de colunas ou assinaturas.
2. **Definir entrega verificável:** descrever comportamento desejado, invariantes, riscos e testes de aceitação em poucas linhas.
3. **Fazer alteração coesa e mínima:** evitar reescritas desnecessárias, duplicação de lógica e abstrações especulativas.
4. **Adicionar testes relevantes:** caminho feliz, negação, isolamento tenant, concorrência, rollback e falha quando pertinentes.
5. **Executar validação:** compilação, testes direcionados, suíte unitária e integração afetada. Não declarar sucesso sem observar o resultado.
6. **Revisar segurança e operação:** estados persistidos, timeouts, logs, limpeza de fixtures, migrations e efeitos de reexecução.
7. **Relatar objetivamente:** arquivos modificados, decisão técnica, comandos executados, resultados, limitações e próximo item prioritário.

Evitar uma sequência interminável de “etapas” artificiais. Organizar o trabalho por **marcos e critérios de aceite**. Quando um problema novo surgir, explicar se ele bloqueia o marco atual ou se pode ficar no backlog.

## Ambiente de validação

Use somente `aporia_tracking_test` nos testes de integração. Antes de aplicar migrations, confirme URL, usuário e nome do banco. Os testes JDBC criam um schema isolado por JVM. Preserve o isolamento ao adicionar cenários de recuperação global ou concorrência. Nunca execute `flyway:clean` em ambiente compartilhado ou de produção.

Quando faltarem evidências sobre um protocolo ou sua operação segura, identifique a lacuna e peça o manual ou a captura correspondente.


## Engineering Workflow

- Discover and load relevant skills before starting a task.
- Use Superpowers for non-trivial implementations,
  architectural changes, debugging and code reviews.
- Use available code indexing tools before performing
  broad repository searches.
- Use Context7 to verify unfamiliar or version-specific
  APIs and framework documentation.
- Follow existing architectural patterns.
- Avoid unnecessary abstractions and overengineering.
- Document significant architectural decisions.
- Write meaningful comments explaining why, not what.
- Review security, performance and maintainability.
- Run relevant tests and static analysis before
  declaring a task complete.
- Never claim verification without execution evidence.
- Report which skills and tools were used.
- Report unavailable tools and validation failures.