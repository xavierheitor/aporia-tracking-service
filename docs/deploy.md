# Deploy e operação

Este procedimento sobe **um gateway e um worker** da mesma revisão, com PostgreSQL 16/PostGIS. O caminho abaixo serve para homologação em host novo. Para produção, defina o banco e a rede da instalação, valide a restauração de backup e conclua a homologação com o firmware físico. A publicação fornecida pelo projeto escuta em loopback no host.

## 1. Identificar o ambiente

Execute a partir da raiz do checkout aprovado. O exemplo usa Docker com Compose, Maven com JDK 25, cliente `psql` e um shell com as variáveis da instalação. Registre a revisão e verifique se o checkout corresponde ao artefato a implantar.

```sh
git status --short
git rev-parse HEAD
mvn -version
docker compose version
```

| Configuração | Onde se aplica |
| --- | --- |
| `TRACKING_DB_URL`, `TRACKING_DB_USER`, `TRACKING_DB_PASSWORD` | Gateway e worker. A senha é obrigatória. Ao executar no host, defina a URL explicitamente. O exemplo de desenvolvimento usa porta 55434. |
| `TRACKING_TCP_BIND`, `TRACKING_TCP_PORT` | O listener da JVM usa por padrão `0.0.0.0:5500`. A publicação Docker do host é uma configuração separada. |
| `TRACKING_PROTOCOL`, `TRACKING_TCP_SESSION_IDLE_SECONDS` | Padrões `auto` e `180`. O modo automático atende Suntech e Teltonika na mesma porta. |
| `TRACKING_DISPATCH_OWNER_FILE` | Habilita a transmissão de comandos no gateway. Na implantação inicial de telemetria, deixe a variável ausente. |
| `FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD` | Usadas pelo processo de migração. A URL deve alcançar o banco a partir de onde o Maven é executado. |

O Compose lê as variáveis do shell ou do arquivo local `.env`. Prepare-o a partir de `.env.example` se ainda não existir, proteja-o no host e forneça as credenciais do ambiente. Para produção, use o mecanismo de segredos da instalação. Nos contêineres, a URL JDBC vem de `TRACKING_CONTAINER_DB_URL`. A JVM iniciada diretamente no host usa `TRACKING_DB_URL`. [Compose da aplicação](../compose.services.yaml) · [Exemplo de ambiente](../.env.example)

## 2. Preparar PostgreSQL e a rede

**Com banco já provisionado**, confirme que a aplicação alcança o endereço interno, que o operador alcança a administração e que o servidor tem os arquivos de extensão `postgis` e `btree_gist`. A migration V2 habilita as extensões com `CREATE EXTENSION IF NOT EXISTS`; use uma credencial de migração com os privilégios necessários, ou peça ao administrador para preparar as extensões.

**Em homologação local nova**, use o [Compose do banco](../compose.database.yaml). Em volume vazio, o PostgreSQL cria o banco `aporia_tracking_v2` e o usuário configurado. A imagem inclui PostGIS; a migration V2 habilita a extensão no banco alvo.

```sh
docker compose -f compose.database.yaml up -d --build --wait
docker compose -f compose.database.yaml exec -T postgres sh -c 'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT current_database(), current_user"'
```

O projeto `aporia-tracking-db` cria a rede `aporia-tracking-db_default` e o volume `aporia-tracking-db_postgres-data`. A porta no host é `TRACKING_DATABASE_PORT` (55434 por padrão). As credenciais de inicialização só são aplicadas a um volume vazio.

**Instalação anterior com dados:** antes da troca, identifique o banco realmente usado pelos processos e seus volumes com `docker ps` e `docker inspect --format '{{json .Mounts}}' NOME_DO_CONTAINER`. Há instalações anteriores com bancos nas portas 55432 e 55433. Confira o nome do banco, o histórico Flyway e as contagens no destino escolhido. Para migrar, interrompa a escrita, faça um backup com `pg_dump -Fc`, restaure em um banco novo e confira extensões, histórico e contagens antes de redirecionar os serviços. O novo nome de projeto cria armazenamento independente. Preserve o volume antigo para recuperação. Para continuar usando o banco atual, configure sua rede externa e a URL JDBC interna nos serviços.

## 3. Construir a imagem e selecionar a rede

```sh
export TRACKING_RELEASE_IMAGE="aporia-tracking-java:$(git rev-parse HEAD)"
docker build -f docker/java/Dockerfile -t "$TRACKING_RELEASE_IMAGE" .
app() { docker compose -f compose.services.yaml "$@"; }
app config --quiet
app config --images
```

A imagem selecionada vale para gateway e worker. O endereço interno padrão é `jdbc:postgresql://postgres:5432/aporia_tracking_v2`. Para outro servidor ou outro banco, configure `TRACKING_CONTAINER_DB_URL` e `TRACKING_DATABASE_NETWORK`. O nome da rede precisa existir quando o Compose dos serviços subir.

## 4. Aplicar o schema e iniciar

Para uma atualização, registre a imagem anterior, faça um backup recuperável e pare os processos da versão anterior antes das migrations, especialmente qualquer gateway que transmita comandos. Preserve o volume do emissor. Em uma instalação nova, siga diretamente para a conferência do destino.

Com o cliente `psql` configurado para o banco alvo, confira explicitamente banco, usuário e endereço. No exemplo local, o operador usa a porta **55434 no host**. Os contêineres usarão a porta **5432 na rede Docker**.

```sh
psql -X -h 127.0.0.1 -p 55434 -U tracking -d aporia_tracking_v2 \
  -c 'SELECT current_database(), current_user, inet_server_addr(), inet_server_port()'

export FLYWAY_URL=jdbc:postgresql://127.0.0.1:55434/aporia_tracking_v2
export FLYWAY_USER=tracking
# Forneça FLYWAY_PASSWORD pelo ambiente do operador.
mvn flyway:info
mvn flyway:migrate
mvn flyway:validate
mvn flyway:info
```

Confira a aplicação bem-sucedida até V10 para esta revisão. O gateway e o worker pressupõem o schema instalado. A imagem da aplicação empacota o serviço com os testes pulados no estágio Docker; execute a suíte em banco de teste antes de promover a revisão. [Primeiros passos e testes](primeiros-passos.md)

```sh
app up -d tracking-gateway tracking-worker
app ps
app logs --tail=80 tracking-gateway tracking-worker
```

Procure `Tracking TCP Gateway listening` e `Aporia Tracking Worker started`. A primeira mensagem informa o bind interno. Para conectar um equipamento físico, defina `TRACKING_PUBLISH_ADDRESS` com o **endereço privado alcançável pelo rastreador** e recrie o gateway e configure o firewall e a rota da APN/VPN. Confira o mapeamento efetivo com `app port tracking-gateway 5500`. No Compose fornecido, `127.0.0.1:5500` aceita somente conexões locais ao host.

## 5. Verificar o fluxo com dados

Use um equipamento de homologação, configure-o para a porta publicada, localize sua pendência e aprove-a no tenant de teste. Para obter posição, associe o equipamento a um ativo com vigência que cubra o instante da leitura. Em seguida, observe **uma nova conexão** e envie um quadro com posição válida. [Descoberta e aprovação](gateway-tcp.md)

Em uma sessão administrativa de `psql` conectada ao destino, substitua os dois UUIDs pelos do ensaio. As consultas abaixo permitem distinguir uma falha de rede, de ingestão ou de processamento sem exibir o payload.

```sql
\set tenant 'UUID_DO_TENANT'
\set device 'UUID_DO_DISPOSITIVO'

SELECT status, attempts, error_code, received_at, available_at
FROM raw_packets
WHERE tenant_id = :'tenant'::uuid AND device_id = :'device'::uuid
ORDER BY received_at DESC LIMIT 10;

SELECT recorded_at, received_at, reading_index
FROM positions
WHERE tenant_id = :'tenant'::uuid AND device_id = :'device'::uuid
ORDER BY received_at DESC LIMIT 10;
```

| Sintoma | Onde verificar |
| --- | --- |
| Equipamento não aparece na fila | IP e porta vistos pelo rastreador, bind publicado, modo de protocolo e logs do gateway. Para Teltonika, o login precisa ser seguido de um lote válido. |
| Aprovado, mas sem recepção bruta | Reconexão, cadastro habilitado e acesso JDBC do gateway. |
| Pacotes ficam `pending` | Logs do worker, `available_at` e `error_code`. Para `ASSIGNMENT_NOT_FOUND`, confira a vigência da associação no horário registrado pelo aparelho. |
| Pacote `processed` sem posição nova | Confira se há fix válido, se o quadro era apenas presença/resposta ou se os bytes já foram processados. |
| Serviço reinicia sem conectar ao banco | Confira a URL dentro do Compose, a rede externa e as credenciais. `localhost` dentro do contêiner aponta para a interface de loopback do próprio contêiner. |

## 6. Habilitar comandos e recuperar uma versão

Em homologação de comandos, habilite o arquivo do emissor no ambiente do gateway. O volume `dispatch-owner` pertence ao projeto `aporia-tracking-app` e conserva o UUID entre recriações. Só a consulta Suntech de firmware está habilitada pelo catálogo atual.

```sh
export TRACKING_DISPATCH_OWNER_FILE=/var/lib/aporia-dispatch/owner
app up -d tracking-gateway
```

Para desabilitar o envio, remova a definição também de `.env`, caso tenha sido gravada ali. Deixe a variável ausente, pois um caminho vazio não é um arquivo de propriedade válido. Recrie apenas o gateway com a configuração de telemetria.

```sh
unset TRACKING_DISPATCH_OWNER_FILE
app up -d --force-recreate tracking-gateway
```

Para voltar o binário, exporte em `TRACKING_RELEASE_IMAGE` a tag anterior **compatível com o schema instalado**, confirme com `app config --images` e recrie os serviços. A troca da imagem preserva as migrations aplicadas. Se a versão anterior exigir outro schema, faça recuperação coordenada a partir de um backup validado em instância isolada. Antes de reativar comandos após restauração, reconcilie as tentativas em curso com o emissor original encerrado. Preserve o arquivo e o volume de propriedade entre recriações.


O arquivo de propriedade requer um sistema de arquivos local com bloqueios POSIX. Preserve o mesmo arquivo enquanto houver uma instância emissora ativa. Se ele for perdido, confirme o encerramento do emissor original e reconcilie as tentativas em `transmitting` antes de retomar o envio.

Para monitorar a instalação, acompanhe a idade do pacote pendente mais antigo, comandos vencidos ainda em fila e crescimento de `delivery_unknown`. Os logs do dispatcher identificam tenant, dispositivo, comando e tentativa. Faça backup com `pg_dump -Fc`, guarde uma cópia protegida fora do host e ensaie a restauração com `pg_restore --exit-on-error` em uma instância isolada. Antes de expurgar comandos, considere que apagar o registro também libera sua chave idempotente.

## Capacidade para 200 rastreadores

Com 200 equipamentos enviando uma posição por minuto, a entrada média é de 3,33 posições por segundo. Se os relógios estiverem alinhados ou a rede voltar depois de uma interrupção, várias leituras podem chegar juntas. Use como primeiro critério de capacidade que uma rajada de 200 posições seja persistida antes da próxima rodada de 60 segundos.

O ensaio optativo usa um gateway, um worker e PostgreSQL/PostGIS em schema isolado. Abre 200 conexões TCP em loopback, uma por equipamento Suntech cadastrado, e envia três rodadas em intervalos de um minuto pelos mesmos sockets. Para cada rodada exige uma posição histórica e um evento por recepção, 200 últimas posições e todos os pacotes processados na primeira tentativa. O tempo registrado vai do início dos envios até a observação das posições confirmadas no banco, com consulta a cada 100 ms. O teste normal de integração cobre os protocolos; esta medição de capacidade usa um quadro STT por posição.

Com as três variáveis de conexão do banco de testes configuradas conforme os [primeiros passos](primeiros-passos.md), execute:

```sh
TRACKING_TEST_FLEET=200 mvn -Plint '-Dtest=GatewayCommandCycleIT#processesTwoHundredDevicesEveryMinute' test
```

No ensaio local de 23/11/2026 em macOS ARM64, JVM 27 com alvo de compilação Java 25 e PostgreSQL/PostGIS de testes, as três rodadas levaram 1,76 s, 1,57 s e 1,37 s. Foram verificadas 600 posições e 600 eventos, com todos os pacotes processados na primeira tentativa. Esta medição curta cobre o envio sincronizado em conexões locais, com o dispatcher habilitado e sem comandos enfileirados.

**Operação inicial:** um gateway, um worker e o banco. Observe a fila em vários instantes, inclusive durante o pico. A consulta abaixo mede apenas pacotes que já podem ser processados. Retentativas programadas para o futuro ficam fora dessa contagem.

```sql
SELECT count(*) AS prontos,
       now() - min(available_at) AS espera_do_mais_antigo
FROM raw_packets
WHERE status = 'pending' AND available_at <= now();
```

Se a espera cresce de um minuto para o outro, verifique erros de processamento e saturação do PostgreSQL. Havendo capacidade no banco, é possível subir um segundo consumidor com `docker compose -f compose.services.yaml up -d --scale tracking-worker=2 tracking-worker` e comparar o tempo de drenagem. Réplicas na mesma máquina aproveitam capacidade disponível, mas compartilham o risco de indisponibilidade do host.

Em operação contínua são **288.000 posições por dia**, ou **8,64 milhões em 30 dias**. Quando cada posição válida chega em um pacote único, crescem também as tabelas de recepções, mensagens, posições e eventos, além de seus índices. Meça o espaço real com uma amostra representativa para definir retenção, espaço de backup e margem para WAL. Para fechar o dimensionamento, repita o ensaio no hardware de homologação e inclua o perfil real de reconexão, lotes armazenados offline e indisponibilidade do banco.

## Gateways atrás do mesmo IP

Configure um balanceador TCP com IP e porta estáveis, verificação de saúde TCP e um timeout de inatividade superior ao intervalo esperado entre mensagens. Distribua os gateways por hosts distintos para tolerar a queda de um host. Uma conexão permanece no gateway que a aceitou. Após uma queda, o equipamento precisa abrir outra conexão para que o balanceador o encaminhe a uma réplica saudável.

Todas as réplicas devem usar o mesmo PostgreSQL primário. Para atender comandos em qualquer réplica, habilite o dispatcher em cada uma com seu próprio arquivo de proprietário em volume persistente exclusivo. Uma réplica adicional deve iniciar com um volume vazio para gerar seu próprio UUID. Somente a substituta de uma instância encerrada deve reutilizar o volume dela. O Compose fornecido publica uma porta fixa e define um volume de proprietário por projeto. Para usar o mesmo manifesto Compose em vários hosts, execute um projeto por host, configure o endereço publicado para o balanceador e aponte todos para o mesmo banco. Em um único host, atribua a cada réplica um projeto, um volume e uma porta de publicação próprios.

Na primeira implantação da V10, pare os gateways antigos, aplique a migration pelo procedimento acima e suba as réplicas atualizadas. Um binário anterior ignora o identificador de sessão. Depois dessa atualização, para manutenção, retire a réplica da rotação, encerre o processo e deixe os equipamentos reconectarem ao IP habitual. Se um equipamento ficar com admissão adiada após a queda de um emissor, restaure a instância com o volume original para reconciliar a tentativa. O balanceador e o PostgreSQL também precisam de redundância para tolerar a perda desses componentes. [Coordenação entre réplicas](arquitetura.md#coordenação-entre-réplicas)
