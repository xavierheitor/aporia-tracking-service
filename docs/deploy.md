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

O Compose carrega credenciais do arquivo local `.env`. Prepare-o a partir de `.env.example` se ainda não existir, proteja-o no host e forneça as credenciais do ambiente. Para produção, use o mecanismo de segredos da instalação. Os valores explícitos em `environment` no Compose prevalecem sobre `env_file`, inclusive a URL JDBC nos dois serviços. [Compose da aplicação](../compose.app.yaml) · [Exemplo de ambiente](../.env.example)

## 2. Preparar PostgreSQL e a rede

**Com banco já provisionado**, confirme que a aplicação alcança o endereço interno, que o operador alcança a administração e que o servidor tem os arquivos de extensão `postgis` e `btree_gist`. A migration V2 habilita as extensões com `CREATE EXTENSION IF NOT EXISTS`; use uma credencial de migração com os privilégios necessários, ou peça ao administrador para preparar as extensões.

**Em homologação local nova**, os comandos abaixo usam o Compose de desenvolvimento. Ele está configurado para ARM64, publica o banco em `127.0.0.1:55434` e traz uma credencial de desenvolvimento. O nome de projeto explícito cria a rede `aporia-tracking-service_default`, que o Compose da aplicação espera encontrar.

```sh
docker build --platform linux/arm64 -f docker/postgres/Dockerfile -t aporia-postgres-postgis:16 .
docker compose -p aporia-tracking-service -f compose.yaml up -d --wait postgres
docker compose -p aporia-tracking-service -f compose.yaml exec -T postgres \
  psql -X -U tracking -d postgres -c '\l'
```

Se o banco `aporia_tracking_v2` estiver ausente, crie-o uma vez:

```sh
docker compose -p aporia-tracking-service -f compose.yaml exec -T postgres \
  createdb -U tracking aporia_tracking_v2
```

## 3. Construir a imagem e resolver o endereço interno

```sh
export TRACKING_RELEASE_IMAGE="aporia-tracking-java:$(git rev-parse HEAD)"
docker build -f docker/java/Dockerfile -t "$TRACKING_RELEASE_IMAGE" .
```

O Compose da aplicação traz a tag `:dev` e aponta para `postgres-postgis`. O serviço do banco de desenvolvimento se chama `postgres`. Para esse ambiente, salve o exemplo abaixo como `compose.release.local.yaml` ao lado do Compose principal. Ele usa a revisão construída e o DNS correto. Para uma instalação com banco externo, substitua a URL nos **dois** serviços e configure uma rede externa existente em `networks.tracking-network.name` se a rede da instalação tiver outro nome.

```yaml
services:
  tracking-gateway:
    image: ${TRACKING_RELEASE_IMAGE:?informe a imagem da revisão}
    environment:
      TRACKING_DB_URL: jdbc:postgresql://postgres:5432/aporia_tracking_v2
  tracking-worker:
    image: ${TRACKING_RELEASE_IMAGE:?informe a imagem da revisão}
    environment:
      TRACKING_DB_URL: jdbc:postgresql://postgres:5432/aporia_tracking_v2
```

Nos comandos seguintes, esta função preserva a ordem dos arquivos Compose:

```sh
app() {
  docker compose -f compose.app.yaml -f compose.release.local.yaml "$@"
}
app config --quiet
app config --images
```

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

Confira a aplicação bem-sucedida até V9 para esta revisão. O gateway e o worker pressupõem o schema instalado. A imagem da aplicação empacota o serviço com os testes pulados no estágio Docker; execute a suíte em banco de teste antes de promover a revisão. [Primeiros passos e testes](primeiros-passos.md)

```sh
app up -d tracking-gateway tracking-worker
app ps
app logs --tail=80 tracking-gateway tracking-worker
```

Procure `Tracking TCP Gateway listening` e `Aporia Tracking Worker started`. A primeira mensagem informa o bind interno. Para conectar um equipamento físico, publique a porta no **endereço privado alcançável pelo rastreador** e configure o firewall e a rota da APN/VPN. Confira o mapeamento efetivo com `app port tracking-gateway 5500`. No Compose fornecido, `127.0.0.1:5500` aceita somente conexões locais ao host.

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

Em homologação de comandos, mantenha a mesma imagem e acrescente o arquivo de comandos ao **mesmo projeto Compose**. Ele configura o arquivo de propriedade em um volume persistente. Só a consulta Suntech de firmware está habilitada pelo catálogo atual.

```sh
app -f compose.commands.yaml config --quiet
app -f compose.commands.yaml up -d
```

Confira envio e retorno pelo roteiro do [ciclo de comandos](comandos.md). Ao desabilitar envio, pare o gateway emissor e recrie o serviço com a configuração de telemetria. O worker pode continuar ativo.

```sh
app -f compose.commands.yaml stop tracking-gateway
app up -d --force-recreate tracking-gateway
```

Para voltar o binário, exporte em `TRACKING_RELEASE_IMAGE` a tag anterior **compatível com o schema instalado**, confirme com `app config --images` e recrie os serviços. A troca da imagem preserva as migrations aplicadas. Se a versão anterior exigir outro schema, faça recuperação coordenada a partir de um backup validado em instância isolada. Antes de reativar comandos após restauração, reconcilie as tentativas em curso com o emissor original encerrado. Preserve o arquivo e o volume de propriedade entre recriações. [Backup, recuperação de tentativas e aceite físico](comandos.md)
