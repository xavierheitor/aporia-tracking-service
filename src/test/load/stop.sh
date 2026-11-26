
#!/usr/bin/env bash

set -euo pipefail

cd "$(dirname "$0")/../../.."

docker compose --profile app stop \
    gateway worker monitor

docker network disconnect \
    aporia-tracking-service_backend \
    "$(docker compose --profile test ps -q postgres-test)"

docker compose --profile app up -d \
    --no-build \
    --pull never \
    --force-recreate \
    --wait \
    gateway worker monitor

echo "Development services restored"
