
#!/usr/bin/env bash
# Restaura o ambiente de desenvolvimento após um benchmark.
#
# Para gateway, worker e monitor, desconecta o banco de testes da rede da
# aplicação, sobe o postgres de desenvolvimento e recria os serviços apontando
# para a URL padrão. Confere a URL efetiva antes de encerrar.

set -euo pipefail

# Executa a partir da raiz do repositório.
cd "$(dirname "$0")/../../.."

NETWORK="aporia-tracking-service_backend"

# URL que os serviços devem ter ao final da restauração.
EXPECTED_URL="jdbc:postgresql://postgres:5432/aporia_tracking"

# Recusa restaurar se o ambiente não estiver com a configuração de desenvolvimento.
if [[ "${TRACKING_CONTAINER_DB_URL:-}" != "$EXPECTED_URL" ]]; then
    echo "Development database configuration is not loaded"
    exit 1
fi

if [[ "${TRACKING_TCP_PORT:-5500}" != "5500" ]]; then
    echo "Unexpected development TCP port"
    exit 1
fi

# Para os serviços de benchmark antes de mexer na rede.
docker compose --profile app stop \
    gateway worker monitor

POSTGRES_ID="$(docker compose --profile test ps -q postgres-test)"

# Remove o alias postgres-load para o banco de testes voltar a ficar isolado.
if [[ -n "$POSTGRES_ID" ]]; then

    if docker inspect "$POSTGRES_ID" \
        --format '{{json .NetworkSettings.Networks}}' \
        | grep -Fq "\"$NETWORK\""; then

        docker network disconnect \
            "$NETWORK" \
            "$POSTGRES_ID"
    fi

fi

# Sobe o banco de desenvolvimento e recria os serviços com a URL padrão.
docker compose --profile dev up -d \
    --no-build \
    --pull never \
    --wait \
    postgres

docker compose --profile app up -d \
    --no-build \
    --pull never \
    --force-recreate \
    --wait \
    gateway worker monitor

# Confere a URL efetiva dentro de cada contêiner restaurado.
for service in gateway worker monitor; do

    ACTUAL_URL="$(docker compose --profile app exec -T \
        "$service" printenv TRACKING_DB_URL)"

    if [[ "$ACTUAL_URL" != "$EXPECTED_URL" ]]; then
        echo "Unexpected development database: $service"

        docker compose --profile app stop \
            gateway worker monitor

        exit 1
    fi

done

echo "Development services restored"
