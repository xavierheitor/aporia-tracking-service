
#!/usr/bin/env bash

set -euo pipefail

cd "$(dirname "$0")/../../.."

set -a
source runtime/load-testing/credentials.env
set +a

export TRACKING_CONTAINER_DB_URL="jdbc:postgresql://postgres-load:5432/aporia_tracking_test?currentSchema=tracking_load,public"

export TRACKING_GATEWAY_DB_PASSWORD="$LOAD_GATEWAY_DB_PASSWORD"
export TRACKING_WORKER_DB_PASSWORD="$LOAD_WORKER_DB_PASSWORD"
export TRACKING_MONITOR_DB_PASSWORD="$LOAD_MONITOR_DB_PASSWORD"

export TRACKING_MONITOR_DB_USER="tracking_monitor"
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

echo "Benchmark services started on port 15500"
