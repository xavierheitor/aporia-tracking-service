
#!/usr/bin/env bash
# Prepara e sobe o ambiente de benchmark.
#
# Efeitos colaterais:
#   - lê runtime/load-testing/credentials.env;
#   - sobe o postgres-test e confere o schema/migrations;
#   - conecta o banco de testes à rede da aplicação com o alias postgres-load;
#   - recria gateway, worker e monitor apontando para o banco de benchmark.
#
# Não use este script contra desenvolvimento ou produção. Ele para os serviços
# atuais antes de reconfigurá-los.

set -euo pipefail

# Executa a partir da raiz do repositório, independentemente do diretório atual.
cd "$(dirname "$0")/../../.."

NETWORK="aporia-tracking-service_backend"
BENCHMARK_URL="jdbc:postgresql://postgres-load:5432/aporia_tracking_test?currentSchema=tracking_load,public"

# Credenciais exclusivas do benchmark; não versionadas.
set -a
source runtime/load-testing/credentials.env
set +a

# Falha cedo se alguma credencial obrigatória estiver ausente.
: "${LOAD_GATEWAY_DB_PASSWORD:?required}"
: "${LOAD_WORKER_DB_PASSWORD:?required}"
: "${LOAD_MONITOR_DB_PASSWORD:?required}"
: "${TRACKING_TEST_DB_USER:?required}"

if [[ "$TRACKING_TEST_DB_USER" != "tracking_test_admin" ]]; then
    echo "Unexpected test database user"
    exit 1
fi

# Sobe apenas o banco de testes, sem reconstruir imagens.
docker compose --profile test up -d \
    --no-build \
    --pull never \
    --wait \
    postgres-test

POSTGRES_ID="$(docker compose --profile test ps -q postgres-test)"

# Confere que as dez migrations do schema de benchmark foram aplicadas.
SCHEMA_STATUS="$(docker compose --profile test exec -T \
    postgres-test \
    psql -X -At \
    -U "$TRACKING_TEST_DB_USER" \
    -d aporia_tracking_test \
    -v ON_ERROR_STOP=1 \
    -c "SELECT count(*) FROM tracking_load.flyway_schema_history WHERE success AND version IS NOT NULL")"

if [[ "$SCHEMA_STATUS" != "10" ]]; then
    echo "Benchmark migrations are not ready"
    exit 1
fi

# A rede backend precisa existir para receber o alias postgres-load.
docker network inspect "$NETWORK" >/dev/null

# Para os serviços atuais antes de trocar a URL do banco.
docker compose --profile app stop \
    gateway worker monitor

# Conecta o banco de testes à rede da aplicação, se ainda não estiver nela.
if ! docker inspect "$POSTGRES_ID" \
    --format '{{json .NetworkSettings.Networks}}' \
    | grep -Fq "\"$NETWORK\""; then

    docker network connect \
        --alias postgres-load \
        "$NETWORK" \
        "$POSTGRES_ID"
fi

# Aponta os serviços para o banco de benchmark e para as roles de carga.
export TRACKING_CONTAINER_DB_URL="$BENCHMARK_URL"

export TRACKING_GATEWAY_DB_PASSWORD="$LOAD_GATEWAY_DB_PASSWORD"
export TRACKING_WORKER_DB_PASSWORD="$LOAD_WORKER_DB_PASSWORD"
export TRACKING_MONITOR_DB_PASSWORD="$LOAD_MONITOR_DB_PASSWORD"

export TRACKING_MONITOR_DB_USER="tracking_monitor"
# Evita poluir o GlitchTip com falhas esperadas do benchmark.
export TRACKING_SENTRY_ENVIRONMENT="benchmark"
export TRACKING_CONTAINER_SENTRY_DSN=""

export TRACKING_PUBLISH_ADDRESS="127.0.0.1"
export TRACKING_TCP_PORT="15500"
export TRACKING_PROTOCOL="auto"

docker compose --profile app up -d \
    --no-build \
    --pull never \
    --force-recreate \
    --wait \
    gateway worker monitor

# Verifica dentro de cada contêiner que a URL efetiva é a de benchmark.
for service in gateway worker monitor; do

    ACTUAL_URL="$(docker compose --profile app exec -T \
        "$service" printenv TRACKING_DB_URL)"

    if [[ "$ACTUAL_URL" != "$BENCHMARK_URL" ]]; then
        echo "Unsafe database configuration: $service"

        docker compose --profile app stop \
            gateway worker monitor

        exit 1
    fi

done

# A porta 15500 é a única publicada durante o benchmark.
ACTUAL_PORT="$(docker compose --profile app port gateway 5500)"

if [[ "$ACTUAL_PORT" != "127.0.0.1:15500" ]]; then
    echo "Unexpected benchmark port"

    docker compose --profile app stop \
        gateway worker monitor

    exit 1
fi

echo "Benchmark services started on 127.0.0.1:15500"
