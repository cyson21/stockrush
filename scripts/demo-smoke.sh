#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/infra/demo/docker-compose.yml"
ENV_FILE="$ROOT_DIR/infra/demo/.env"
ENV_EXAMPLE="$ROOT_DIR/infra/demo/.env.example"

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

check_url "gateway-health" "http://localhost:${GATEWAY_HOST_PORT:-28080}/actuator/health"
check_url "catalog-health" "http://localhost:${CATALOG_HOST_PORT:-28081}/actuator/health"
check_url "inventory-health" "http://localhost:${INVENTORY_HOST_PORT:-28082}/actuator/health"
check_url "order-health" "http://localhost:${ORDER_HOST_PORT:-28083}/actuator/health"
check_url "payment-health" "http://localhost:${PAYMENT_HOST_PORT:-28084}/actuator/health"
check_url "promotion-health" "http://localhost:${PROMOTION_HOST_PORT:-28085}/actuator/health"
check_url "fulfillment-health" "http://localhost:${FULFILLMENT_HOST_PORT:-28086}/actuator/health"
check_url "read-model-health" "http://localhost:${READ_MODEL_HOST_PORT:-28087}/actuator/health"
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
  --orders 3 \
  --initial-stock 20 \
  --quantity 1 \
  --max-attempts 12
