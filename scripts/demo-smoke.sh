#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/infra/demo/docker-compose.yml"
ENV_FILE="$ROOT_DIR/infra/demo/.env"
ENV_EXAMPLE="$ROOT_DIR/infra/demo/.env.example"
skip_burst=false

usage() {
  cat <<'EOF'
Usage: ./scripts/demo-smoke.sh [options]

Options:
  --skip-burst  Run only health checks and demo-order-flow
  -h, --help    Show this help
EOF
}

while (($#)); do
  case "$1" in
    --skip-burst)
      skip_burst=true
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

  if curl -fsS --max-time 10 "$url" >/dev/null; then
    printf '[ok] %s %s\n' "$name" "$url"
    return
  fi

  printf '[fail] %s %s\n' "$name" "$url" >&2
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps >&2 || true
  exit 1
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
check_url "gateway-read-model" "http://localhost:${GATEWAY_HOST_PORT:-28080}/api/read-model/admin/orders?page=0&size=1"

"$ROOT_DIR/tools/local-e2e/local-e2e" demo-order-flow \
  --catalog-url "http://localhost:${CATALOG_HOST_PORT:-28081}" \
  --inventory-url "http://localhost:${INVENTORY_HOST_PORT:-28082}" \
  --order-url "http://localhost:${ORDER_HOST_PORT:-28083}" \
  --order-api-url "http://localhost:${GATEWAY_HOST_PORT:-28080}" \
  --outbox-api-url "http://localhost:${GATEWAY_HOST_PORT:-28080}" \
  --payment-url "http://localhost:${PAYMENT_HOST_PORT:-28084}" \
  --promotion-url "http://localhost:${PROMOTION_HOST_PORT:-28085}" \
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
