#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/infra/demo/docker-compose.yml"
ENV_FILE="$ROOT_DIR/infra/demo/.env"
ENV_EXAMPLE="$ROOT_DIR/infra/demo/.env.example"
skip_burst=false
kafka_outage=false

usage() {
  cat <<'EOF'
Usage: ./scripts/demo-smoke.sh [options]

Options:
  --skip-burst    Run only health checks and demo-order-flow
  --kafka-outage  Run opt-in Kafka recovery smoke using docker compose pause kafka / unpause kafka
  -h, --help      Show this help
EOF
}

while (($#)); do
  case "$1" in
    --skip-burst)
      skip_burst=true
      shift
      ;;
    --kafka-outage)
      kafka_outage=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ ! -f "$ENV_FILE" ]]; then
  cp "$ENV_EXAMPLE" "$ENV_FILE"
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

check_url() {
  local name="$1"
  local url="$2"
  local header="${3:-}"
  local curl_opts=(--max-time 10)

  if [[ -n "$header" ]]; then
    curl_opts+=("-H" "$header")
  fi

  if curl -fsS "${curl_opts[@]}" "$url" >/dev/null; then
    printf '[ok] %s %s\n' "$name" "$url"
    return
  fi

  printf '[fail] %s %s\n' "$name" "$url" >&2
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps >&2 || true
  exit 1
}

wait_for_keycloak_ready() {
  local keycloak_host_port="$1"
  local discovery_url="http://localhost:${keycloak_host_port}/realms/stockrush/.well-known/openid-configuration"

  for attempt in $(seq 1 120); do
    if curl -fsS --max-time 5 "$discovery_url" >/dev/null; then
      return 0
    fi

    printf '[wait] keycloak ready attempt %s/120\n' "$attempt"
    sleep 1
  done

  printf '[fail] keycloak realm did not become ready at %s\n' "$discovery_url" >&2
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps >&2 || true
  exit 1
}

get_keycloak_token() {
  local keycloak_host_port="$1"
  local client_id="$2"
  local username="$3"
  local password="$4"
  local response
  local token

  response="$(curl -fsS --max-time 10 -X POST "http://localhost:${keycloak_host_port}/realms/stockrush/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode "client_id=${client_id}" \
    --data-urlencode 'grant_type=password' \
    --data-urlencode "username=${username}" \
    --data-urlencode "password=${password}")"

  token="$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token", ""))')"

  if [[ -z "$token" ]]; then
    printf '[fail] failed to parse access token from keycloak response\n' >&2
    printf '%s\n' "$response" >&2
    return 1
  fi

  printf '%s' "$token"
}

check_actuator_endpoints() {
  local name="$1"
  local base_url="$2"

  check_url "$name-health" "$base_url/actuator/health"
  check_url "$name-info" "$base_url/actuator/info"
  check_url "$name-metrics" "$base_url/actuator/metrics"
}

check_actuator_endpoints "gateway" "http://localhost:${GATEWAY_HOST_PORT:-28080}"
check_actuator_endpoints "catalog" "http://localhost:${CATALOG_HOST_PORT:-28081}"
check_actuator_endpoints "inventory" "http://localhost:${INVENTORY_HOST_PORT:-28082}"
check_actuator_endpoints "order" "http://localhost:${ORDER_HOST_PORT:-28083}"
check_actuator_endpoints "payment" "http://localhost:${PAYMENT_HOST_PORT:-28084}"
check_actuator_endpoints "promotion" "http://localhost:${PROMOTION_HOST_PORT:-28085}"
check_actuator_endpoints "fulfillment" "http://localhost:${FULFILLMENT_HOST_PORT:-28086}"
check_actuator_endpoints "read-model" "http://localhost:${READ_MODEL_HOST_PORT:-28087}"
check_url "customer-app" "http://localhost:${CUSTOMER_APP_HOST_PORT:-15173}/"
check_url "admin-app" "http://localhost:${ADMIN_APP_HOST_PORT:-15174}/"
check_url "catalog-products" "http://localhost:${CATALOG_HOST_PORT:-28081}/api/products?status=ON_SALE"
check_url "inventory-stocks" "http://localhost:${INVENTORY_HOST_PORT:-28082}/api/stocks"

KEYCLOAK_HOST_PORT="${KEYCLOAK_HOST_PORT:-28088}"
KEYCLOAK_SMOKE_CLIENT_ID="${KEYCLOAK_SMOKE_CLIENT_ID:-stockrush-demo-smoke}"
KEYCLOAK_SMOKE_ADMIN_USERNAME="${KEYCLOAK_SMOKE_ADMIN_USERNAME:-admin.demo@stockrush.local}"
KEYCLOAK_SMOKE_ADMIN_PASSWORD="${KEYCLOAK_SMOKE_ADMIN_PASSWORD:-demo-admin-pass}"
KEYCLOAK_SMOKE_CUSTOMER_USERNAME="${KEYCLOAK_SMOKE_CUSTOMER_USERNAME:-customer.demo@stockrush.local}"
KEYCLOAK_SMOKE_CUSTOMER_PASSWORD="${KEYCLOAK_SMOKE_CUSTOMER_PASSWORD:-demo-customer-pass}"

wait_for_keycloak_ready "$KEYCLOAK_HOST_PORT"

ADMIN_BEARER_TOKEN="$(get_keycloak_token "$KEYCLOAK_HOST_PORT" "$KEYCLOAK_SMOKE_CLIENT_ID" "$KEYCLOAK_SMOKE_ADMIN_USERNAME" "$KEYCLOAK_SMOKE_ADMIN_PASSWORD")"
CUSTOMER_BEARER_TOKEN="$(get_keycloak_token "$KEYCLOAK_HOST_PORT" "$KEYCLOAK_SMOKE_CLIENT_ID" "$KEYCLOAK_SMOKE_CUSTOMER_USERNAME" "$KEYCLOAK_SMOKE_CUSTOMER_PASSWORD")"

printf '[ok] obtained admin/customer smoke tokens\n'

check_url "gateway-read-model" "http://localhost:${GATEWAY_HOST_PORT:-28080}/api/read-model/admin/orders?page=0&size=1" "Authorization: Bearer $ADMIN_BEARER_TOKEN"

if [[ -z "$CUSTOMER_BEARER_TOKEN" ]]; then
  printf '[fail] customer bearer token is empty\n' >&2
  exit 1
fi

"$ROOT_DIR/tools/local-e2e/local-e2e" demo-order-flow \
  --catalog-url "http://localhost:${CATALOG_HOST_PORT:-28081}" \
  --inventory-url "http://localhost:${INVENTORY_HOST_PORT:-28082}" \
  --order-url "http://localhost:${ORDER_HOST_PORT:-28083}" \
  --order-api-url "http://localhost:${GATEWAY_HOST_PORT:-28080}" \
  --outbox-api-url "http://localhost:${GATEWAY_HOST_PORT:-28080}" \
  --payment-url "http://localhost:${PAYMENT_HOST_PORT:-28084}" \
  --promotion-url "http://localhost:${PROMOTION_HOST_PORT:-28085}" \
  --admin-bearer-token "$ADMIN_BEARER_TOKEN" \
  --customer-bearer-token "$CUSTOMER_BEARER_TOKEN" \
  --relay-mode automatic \
  --orders 3 \
  --initial-stock 20 \
  --quantity 1 \
  --max-attempts 30 \
  --wait-seconds 1

if [[ "$skip_burst" != true ]]; then
  "$ROOT_DIR/tools/local-e2e/local-e2e" burst-idempotency \
    --catalog-url "http://localhost:${CATALOG_HOST_PORT:-28081}" \
    --inventory-url "http://localhost:${INVENTORY_HOST_PORT:-28082}" \
    --order-url "http://localhost:${ORDER_HOST_PORT:-28083}" \
    --order-api-url "http://localhost:${GATEWAY_HOST_PORT:-28080}" \
    --outbox-api-url "http://localhost:${GATEWAY_HOST_PORT:-28080}" \
    --payment-url "http://localhost:${PAYMENT_HOST_PORT:-28084}" \
    --promotion-url "http://localhost:${PROMOTION_HOST_PORT:-28085}" \
    --admin-bearer-token "$ADMIN_BEARER_TOKEN" \
    --customer-bearer-token "$CUSTOMER_BEARER_TOKEN" \
    --relay-mode automatic \
    --orders 12 \
    --initial-stock 4 \
    --quantity 1 \
    --idempotency-replays 3 \
    --relay-workers 4 \
    --stability-waves 2 \
    --max-attempts 30 \
    --wait-seconds 1
fi

if [[ "$kafka_outage" == true ]]; then
  "$ROOT_DIR/tools/local-e2e/local-e2e" kafka-outage-recovery \
    --compose-file "$COMPOSE_FILE" \
    --env-file "$ENV_FILE" \
    --kafka-service kafka \
    --catalog-url "http://localhost:${CATALOG_HOST_PORT:-28081}" \
    --inventory-url "http://localhost:${INVENTORY_HOST_PORT:-28082}" \
    --order-url "http://localhost:${ORDER_HOST_PORT:-28083}" \
    --order-api-url "http://localhost:${GATEWAY_HOST_PORT:-28080}" \
    --outbox-api-url "http://localhost:${GATEWAY_HOST_PORT:-28080}" \
    --payment-url "http://localhost:${PAYMENT_HOST_PORT:-28084}" \
    --promotion-url "http://localhost:${PROMOTION_HOST_PORT:-28085}" \
    --admin-bearer-token "$ADMIN_BEARER_TOKEN" \
    --customer-bearer-token "$CUSTOMER_BEARER_TOKEN" \
    --relay-mode automatic \
    --orders 1 \
    --initial-stock 3 \
    --quantity 1 \
    --outage-observation-seconds 2 \
    --max-attempts 30 \
    --wait-seconds 1
fi
