#!/usr/bin/env bash
# kind 배포 전 매니페스트 렌더링/도구/자격 검증을 수행합니다.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_TAG="${STOCKRUSH_IMAGE_TAG:-latest-demo}"
GHCR_TOKEN_FILE="${STOCKRUSH_GHCR_TOKEN_FILE:-$HOME/.config/stockrush/github-token}"

usage() {
  cat <<'EOF'
Usage: ./scripts/kind-preflight.sh [options]

Options:
  --tag <tag>  StockRush GHCR image tag to render. Default: latest-demo
  -h, --help   Show this help
EOF
}

while (($#)); do
  case "$1" in
    --tag)
      IMAGE_TAG="${2:?--tag requires a value}"
      shift 2
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

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf '[fail] %s is not installed.\n' "$1" >&2
    exit 1
  fi
  printf '[ok] %s found\n' "$1"
}

render_manifest() {
  kubectl kustomize "$ROOT_DIR/infra/k8s/kind" | sed "s|:latest-demo|:${IMAGE_TAG}|g"
}

require_rendered_text() {
  local file="$1"
  local pattern="$2"
  local label="$3"

  if grep -q "$pattern" "$file"; then
    printf '[ok] %s\n' "$label"
  else
    printf '[fail] rendered manifest is missing %s\n' "$label" >&2
    exit 1
  fi
}

check_tools() {
  require_command docker
  require_command kind
  require_command kubectl

  if docker info >/dev/null 2>&1; then
    printf '[ok] Docker daemon is reachable\n'
  else
    printf '[fail] Docker daemon is not reachable. Start Docker Desktop first.\n' >&2
    exit 1
  fi
}

check_credentials() {
  if [[ -s "$GHCR_TOKEN_FILE" ]]; then
    printf '[ok] GHCR token file is present\n'
    return
  fi

  if [[ -s "$HOME/.docker/config.json" ]]; then
    printf '[warn] GHCR token file not found. Falling back to Docker config for image pull secret.\n' >&2
    return
  fi

  printf '[warn] GHCR token file and Docker config are both missing. Private GHCR images may fail to pull.\n' >&2
}

check_existing_runtime() {
  local running

  running="$(docker ps --format '{{.Names}} {{.Status}}' | grep -E '^(stockrush-demo-|stockrush-control-plane)' || true)"
  if [[ -n "$running" ]]; then
    printf '[warn] StockRush/kind containers are already running:\n%s\n' "$running" >&2
  else
    printf '[ok] no StockRush/kind containers are running\n'
  fi
}

check_manifest() {
  local rendered

  rendered="$(mktemp)"
  trap "rm -f '$rendered'" EXIT
  render_manifest >"$rendered"

  require_rendered_text "$rendered" 'kind: Namespace' 'namespace render'
  require_rendered_text "$rendered" 'name: stockrush' 'stockrush namespace/name'
  require_rendered_text "$rendered" 'name: kafka-init' 'Kafka init job'
  require_rendered_text "$rendered" 'name: gateway' 'Gateway service/deployment'
  require_rendered_text "$rendered" 'name: customer-app' 'Customer app service/deployment'
  require_rendered_text "$rendered" 'name: admin-app' 'Admin app service/deployment'
  require_rendered_text "$rendered" "stockrush-gateway:${IMAGE_TAG}" "Gateway image tag ${IMAGE_TAG}"
  require_rendered_text "$rendered" 'KAFKA_CONTROLLER_QUORUM_VOTERS' 'Kafka KRaft controller setting'
  require_rendered_text "$rendered" '1@localhost:29093' 'Kafka local controller voter'
  test -f "$ROOT_DIR/infra/k8s/base/postgres.yaml"
  printf '[ok] infra phase source postgres\n'
  test -f "$ROOT_DIR/infra/k8s/base/keycloak.yaml"
  printf '[ok] infra phase source keycloak\n'
  test -f "$ROOT_DIR/infra/k8s/base/backend.yaml"
  printf '[ok] apps phase source backend\n'
  test -f "$ROOT_DIR/infra/k8s/base/gateway.yaml"
  printf '[ok] apps phase source gateway\n'

  printf '[ok] rendered manifest lines: %s\n' "$(wc -l < "$rendered" | tr -d ' ')"
}

check_tools
check_credentials
check_existing_runtime
check_manifest

printf '[ok] kind preflight passed for tag %s\n' "$IMAGE_TAG"
