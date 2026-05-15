#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/infra/demo/docker-compose.yml"
ENV_FILE="$ROOT_DIR/infra/demo/.env"
ENV_EXAMPLE="$ROOT_DIR/infra/demo/.env.example"
EXPECTED_ENV_REV="2026-05-15-security-v1"

PORT_SPECS=(
  "POSTGRES_HOST_PORT:25432:postgres:5432"
  "REDIS_HOST_PORT:26379:redis:6379"
  "KAFKA_HOST_PORT:29092:kafka:9092"
  "KAFKA_UI_PORT:29090:kafka-ui:8080"
  "KEYCLOAK_HOST_PORT:28088:keycloak:8080"
  "GATEWAY_HOST_PORT:28080:gateway:18080"
  "CATALOG_HOST_PORT:28081:catalog-service:18081"
  "INVENTORY_HOST_PORT:28082:inventory-service:18082"
  "ORDER_HOST_PORT:28083:order-service:18083"
  "PAYMENT_HOST_PORT:28084:payment-service:18084"
  "PROMOTION_HOST_PORT:28085:promotion-service:18085"
  "FULFILLMENT_HOST_PORT:28086:fulfillment-service:18086"
  "READ_MODEL_HOST_PORT:28087:read-model-service:18087"
  "CUSTOMER_APP_HOST_PORT:15173:customer-app:8080"
  "ADMIN_APP_HOST_PORT:15174:admin-app:8080"
)

refresh_env=false
skip_port_check=false
compose_args=()

for arg in "$@"; do
  case "$arg" in
    --refresh-env)
      refresh_env=true
      ;;
    --skip-port-check)
      skip_port_check=true
      ;;
    *)
      compose_args+=("$arg")
      ;;
  esac
done

if [[ "$refresh_env" == true ]]; then
  cp "$ENV_EXAMPLE" "$ENV_FILE"
elif [[ ! -f "$ENV_FILE" ]]; then
  cp "$ENV_EXAMPLE" "$ENV_FILE"
fi

if ! grep -q "^DEMO_ENV_REV=$EXPECTED_ENV_REV$" "$ENV_FILE"; then
  printf '[warn] infra/demo/.env does not match the current template revision.\n' >&2
  printf '[warn] Run ./scripts/demo-up.sh --refresh-env to copy the latest demo defaults, or edit infra/demo/.env manually.\n' >&2
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

get_port_value() {
  local name="$1"
  local default_value="$2"
  local value="${!name:-}"

  if [[ -z "$value" ]]; then
    printf '%s' "$default_value"
    return
  fi

  printf '%s' "$value"
}

is_current_demo_port() {
  local service="$1"
  local target_port="$2"
  local host_port="$3"
  local mapped

  mapped="$(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" port "$service" "$target_port" 2>/dev/null || true)"
  case "$mapped" in
    *":$host_port")
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

check_host_ports() {
  local seen_ports=""
  local has_failure=false
  local spec name default_value service target_port port owner

  for spec in "${PORT_SPECS[@]}"; do
    IFS=":" read -r name default_value service target_port <<<"$spec"
    port="$(get_port_value "$name" "$default_value")"

    if [[ ! "$port" =~ ^[0-9]+$ ]] || (( port < 1 || port > 65535 )); then
      printf '[fail] %s must be a TCP port number between 1 and 65535: %s\n' "$name" "$port" >&2
      has_failure=true
      continue
    fi

    case " $seen_ports " in
      *" $port "*)
        printf '[fail] duplicate demo host port %s found at %s\n' "$port" "$name" >&2
        has_failure=true
        ;;
      *)
        seen_ports="$seen_ports $port"
        ;;
    esac

    if command -v lsof >/dev/null 2>&1; then
      if owner="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null)" && [[ -n "$owner" ]]; then
        if is_current_demo_port "$service" "$target_port" "$port"; then
          printf '[ok] %s=%s is already bound by the current demo stack.\n' "$name" "$port"
          continue
        fi

        printf '[fail] %s=%s is already in use on this machine.\n' "$name" "$port" >&2
        printf '%s\n' "$owner" | sed 's/^/  /' >&2
        has_failure=true
      fi
    else
      printf '[warn] lsof is not available; skipping host port availability checks.\n' >&2
      break
    fi
  done

  if [[ "$has_failure" == true ]]; then
    printf '[hint] Edit infra/demo/.env to change the conflicting port, or run ./scripts/demo-up.sh --refresh-env to restore demo defaults.\n' >&2
    exit 1
  fi
}

if [[ "$skip_port_check" != true ]]; then
  check_host_ports
fi

if (( ${#compose_args[@]} > 0 )); then
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --build "${compose_args[@]}"
else
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --build
fi
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps
