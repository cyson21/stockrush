#!/usr/bin/env bash
# 로컬 인프라 자원을 안전하게 정리하며 dry-run과 실제 실행을 분리합니다.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_COMPOSE_FILE="$ROOT_DIR/infra/local/docker-compose.yml"
DEMO_ENV_FILE="$ROOT_DIR/infra/demo/.env"
DEMO_ENV_EXAMPLE="$ROOT_DIR/infra/demo/.env.example"
DEMO_COMPOSE_FILE="$ROOT_DIR/infra/demo/docker-compose.yml"

apply=false
include_demo=false
include_volumes=false
prune_build_cache=false
prune_dangling_images=false

usage() {
  cat <<'EOF'
Usage: ./scripts/docker-clean.sh [options]

Safely cleans StockRush Docker containers and images.
Default mode is dry-run and keeps the stockrush-demo stack running.

Options:
  --apply                 Execute cleanup. Without this, commands are only printed.
  --include-demo          Stop and remove the stockrush-demo stack too.
  --include-volumes       Remove StockRush local/demo PostgreSQL and Redis volumes.
  --prune-build-cache     Prune Docker build cache.
  --prune-dangling-images Remove dangling images.
  -h, --help              Show this help.
EOF
}

while (($#)); do
  case "$1" in
    --apply)
      apply=true
      shift
      ;;
    --include-demo)
      include_demo=true
      shift
      ;;
    --include-volumes)
      include_volumes=true
      shift
      ;;
    --prune-build-cache)
      prune_build_cache=true
      shift
      ;;
    --prune-dangling-images)
      prune_dangling_images=true
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

docker_bin="${DOCKER_BIN:-}"
if [[ -z "$docker_bin" ]]; then
  if command -v docker >/dev/null 2>&1; then
    docker_bin="$(command -v docker)"
  elif [[ -x /Applications/Docker.app/Contents/Resources/bin/docker ]]; then
    docker_bin="/Applications/Docker.app/Contents/Resources/bin/docker"
  else
    printf 'Docker CLI not found. Set DOCKER_BIN or install Docker Desktop.\n' >&2
    exit 1
  fi
fi

run() {
  printf '+'
  printf ' %q' "$@"
  printf '\n'
  if [[ "$apply" == true ]]; then
    "$@"
  fi
}

maybe_run() {
  local description="$1"
  shift
  printf '[cleanup] %s\n' "$description"
  run "$@"
}

printf '[cleanup] mode=%s docker=%s\n' "$([[ "$apply" == true ]] && printf apply || printf dry-run)" "$docker_bin"

maybe_run "Stop duplicate local infrastructure stack and remove orphan containers." \
  "$docker_bin" compose -f "$LOCAL_COMPOSE_FILE" down --remove-orphans

if [[ "$include_demo" == true ]]; then
  if [[ ! -f "$DEMO_ENV_FILE" ]]; then
    run cp "$DEMO_ENV_EXAMPLE" "$DEMO_ENV_FILE"
  fi
  demo_down_args=(--env-file "$DEMO_ENV_FILE" -f "$DEMO_COMPOSE_FILE" down --remove-orphans)
  if [[ "$include_volumes" == true ]]; then
    demo_down_args+=(-v)
  fi
  maybe_run "Stop stockrush-demo stack." "$docker_bin" compose "${demo_down_args[@]}"
else
  if "$docker_bin" container inspect stockrush-demo-kafka-init-1 >/dev/null 2>&1; then
    maybe_run "Remove completed stockrush-demo init container." "$docker_bin" rm stockrush-demo-kafka-init-1
  fi
fi

legacy_images=(
  stockrush-demo-gateway:latest
  stockrush-demo-order-service:latest
  stockrush-demo-inventory-service:latest
  stockrush-demo-payment-service:latest
  stockrush-demo-fulfillment-service:latest
  stockrush-demo-catalog-service:latest
  stockrush-demo-promotion-service:latest
  stockrush-demo-read-model-service:latest
  stockrush-demo-admin-app:latest
  stockrush-demo-customer-app:latest
)

for image in "${legacy_images[@]}"; do
  if "$docker_bin" image inspect "$image" >/dev/null 2>&1; then
    maybe_run "Remove old local build image $image." "$docker_bin" rmi "$image"
  fi
done

if [[ "$include_volumes" == true && "$include_demo" != true ]]; then
  printf '[warn] --include-volumes without --include-demo keeps demo volumes attached when demo is running.\n' >&2
fi

if [[ "$include_volumes" == true ]]; then
  volumes=(
    local_stockrush-postgres-data
    local_stockrush-redis-data
    stockrush-demo_stockrush-demo-postgres-data
    stockrush-demo_stockrush-demo-redis-data
  )
  for volume in "${volumes[@]}"; do
    if "$docker_bin" volume inspect "$volume" >/dev/null 2>&1; then
      maybe_run "Remove StockRush volume $volume." "$docker_bin" volume rm "$volume"
    fi
  done
fi

if [[ "$prune_dangling_images" == true ]]; then
  maybe_run "Remove dangling images." "$docker_bin" image prune -f
fi

if [[ "$prune_build_cache" == true ]]; then
  maybe_run "Remove Docker build cache." "$docker_bin" builder prune -f
fi

printf '[cleanup] Remaining StockRush containers:\n'
"$docker_bin" ps -a --filter "name=stockrush" --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Label "com.docker.compose.project"}}'

printf '[cleanup] Docker disk usage:\n'
"$docker_bin" system df
