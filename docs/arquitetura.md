# Arquitetura e fluxos de execução

O gateway é dono das conexões TCP. O worker é dono do processamento da fila de pacotes brutos. O PostgreSQL guarda a passagem de uma etapa para a outra: o gateway pode confirmar a recepção antes de o worker calcular uma posição. O dispatcher opcional compartilha a JVM do gateway para alcançar as sessões em memória. A API HTTP está prevista para uma etapa posterior.

```mermaid
flowchart LR
    R[Rastreador] <-->|TCP| G[Gateway e sessões]
    G -->|Identidade nova| D[(Descobertas pendentes)]
    A[Operador] -->|Aprova para um tenant| D
    D -->|Cadastro aprovado| C[(Dispositivos)]
    G -->|Consulta cadastro| C
    G -->|Commit da recepção| P[(raw_packets)]
    P -->|Reserva por linha| W[Worker]
    W --> H[(Mensagens, posições e outbox)]
    W --> S[(Respostas de comandos)]
    F[(Fila de comandos)] --> X[Dispatcher no gateway]
    X -->|Escreve na sessão atual| G
```

## Conexão e descoberta

1. O listener aceita o socket. O primeiro byte seleciona um adaptador candidato no modo `auto`. O adaptador cria uma sessão de protocolo para aquela conexão. Seu buffer conserva fragmentos e pode emitir vários quadros a partir da mesma leitura TCP.
2. O gateway consulta o par protocolo e identidade declarada. Para um Suntech desconhecido, valida o conteúdo ASCII. No Teltonika, responde ao login para obter o primeiro lote AVL e valida estrutura, codec e CRC antes da descoberta. O IMEI permite reconhecer reconexões. A marca compatível vem do formato observado.
3. Uma identidade nova fica em `device_discoveries`. O operador consulta a view de pendências e aprova para um tenant. Antes da aprovação, essa conexão termina após a descoberta. A próxima conexão do equipamento habilitado abre uma sessão de dispositivo.
4. O registro de sessões usa tenant e dispositivo como chave. Uma reconexão invalida a sessão anterior. A remoção compara a instância para que o encerramento tardio de um socket antigo preserve a conexão mais recente.
5. Para cada quadro de dados, a ingestão revalida o cadastro e grava bytes, hash e versão do decodificador. Para AVL, o ACK do lote informa o número de registros **após o commit**. Nesse instante os bytes já estão no banco e o processamento das posições ainda pode estar pendente. [Guia de cadastro e protocolos](gateway-tcp.md)

## Threads, conexões e limites

| Quem executa | Trabalho e consequência |
| --- | --- |
| Thread principal do gateway | Aceita conexões no `ServerSocket` com backlog solicitado de 50. Esse número diz respeito à espera por aceite. Cada socket aceito segue para uma tarefa própria. |
| Thread virtual por conexão | Lê o socket, alimenta o adaptador e faz a ingestão JDBC. O prazo inicial é de 30 s e a inatividade de uma sessão identificada tem padrão de 180 s. O socket pode permanecer aberto por várias mensagens. |
| Agendador do dispatcher | Quando habilitado, executa uma rodada de recuperação e percorre as sessões. Aguarda 1 s **após** a rodada para iniciar a próxima. Uma escrita lenta pode atrasar outros dispositivos nessa varredura. |
| Agendador de prazo de escrita | Compartilhado pelas sessões na JVM. Se a escrita exceder 5 s, fecha o socket para desbloquear a operação. A escrita e a invalidação usam o mesmo bloqueio por sessão. |
| Laço principal do worker | Processa até um pacote por chamada, repete imediatamente após trabalho e dorme 1 s quando a fila está vazia. Após erro no laço, espera 3 s antes de continuar. |
| Pool JDBC por JVM | Cada processo mantém seu HikariCP com máximo de 10 conexões, mínimo ocioso de 2 e espera de até 10 s por uma conexão. Com um gateway e um worker, reserve capacidade para até 20 conexões da aplicação, além das administrativas. Uma sessão TCP ociosa não exige uma conexão JDBC exclusiva. |

Ao encerrar, o gateway interrompe o aceite e fecha sockets para liberar as leituras. O worker sinaliza a parada e permite a conclusão do ciclo corrente. Os hooks aguardam até 10 s, e o Compose concede 15 s antes do encerramento forçado. As sessões vivem na memória da instância; um gateway recém-iniciado volta a enxergar cada equipamento quando ele reconecta. [Gateway](../src/main/java/software/aporia/tracking/gateway/TcpGateway.java) · [Sessão TCP](../src/main/java/software/aporia/tracking/gateway/DeviceSession.java) · [Pool JDBC](../src/main/java/software/aporia/tracking/storage/Database.java)

## Do pacote à posição

O worker seleciona uma linha `pending` com `available_at` vencido. O PostgreSQL usa `FOR UPDATE OF rp SKIP LOCKED` para que consumidores concorrentes possam avançar sobre linhas diferentes. O worker verifica o SHA-256, localiza a versão do decodificador gravada na ingestão e compara a identidade interpretada com o cadastro.

Uma transação cobre a mensagem processada, as posições, a atualização de última posição, os eventos de outbox, as respostas aplicáveis e o estado do pacote. Para cada leitura com horário, coordenadas e fix válido, `assignments` resolve o ativo pela vigência **no horário da leitura**. Uma posição atrasada entra no histórico e só avança `last_positions` se for mais recente. O PostGIS recebe longitude e latitude nessa ordem, em SRID 4326.

| Situação | Efeito observável no banco |
| --- | --- |
| Mesmo conteúdo recebido novamente | Cada recepção tem seu pacote bruto. A chave de mensagem por tenant, dispositivo e hash evita duplicar posições e eventos. Respostas são registradas por pacote bruto. |
| Lote AVL com várias leituras | O histórico conserva o índice original de cada leitura válida. Leituras sem fix válido permanecem nos bytes brutos. |
| Resposta Suntech com posição embutida | O mesmo quadro pode produzir posição e resposta. A resposta fica sem vínculo de comando quando falta evidência de correlação. |
| Associação ao ativo ausente | O worker volta ao savepoint e agenda nova tentativa. O atraso é de 10, 30, 60 e 120 s após as quatro primeiras falhas. Na quinta, o pacote fica `rejected`. |
| Pacote inválido ou falha de banco | Falhas de validação tratadas como definitivas ficam registradas no pacote. Uma falha SQL que escapa da transação provoca rollback, preservando a consistência das gravações. |

O evento é persistido em `outbox_events` junto com a posição. O escopo atual termina nessa gravação. [Worker](../src/main/java/software/aporia/tracking/worker/TrackingWorker.java) · [Histórico das migrations](../README.md#leitura-das-migrations)

## Do comando ao rastreador

O código autorizado enfileira a consulta de firmware com tenant, dispositivo, solicitante e chave idempotente. O dispatcher seleciona uma sessão online, reserva o comando e confirma a autorização antes de escrever no socket. A transação de autorização já terminou durante a escrita TCP. A exclusividade do arquivo do emissor coordena a recuperação das tentativas que estavam em transmissão.

`sent` significa que a escrita local terminou. Uma falha que possa ter ocorrido após envio parcial produz `delivery_unknown`. Se o gateway cair após escrever e antes de persistir o resultado, a recuperação também deve tratar a tentativa como incerta. A retomada evita retransmissão automática. Os prazos, o arquivo de propriedade e os limites da correlação Suntech estão no [guia de comandos](comandos.md).
