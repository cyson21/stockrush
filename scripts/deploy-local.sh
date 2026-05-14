#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/infra/demo/docker-compose.yml"
IMAGE_COMPOSE_FILE="$ROOT_DIR/infra/demo/docker-compose.images.yml"
ENV_FILE="$ROOT_DIR/infra/demo/.env"
ENV_EXAMPLE="$ROOT_DIR/infra/demo/.env.example"
TOKEN_FILE="${STOCKRUSH_GITHUB_TOKEN_FILE:-$HOME/.config/stockrush/github-token}"

image_registry="ghcr.io"
image_owner="cyson21"
image_tag="latest-demo"
refresh_env=false
skip_pull=false
skip_smoke=false
login=false

usage() {
  cat <<'EOF'
Usage: ./scripts/deploy-local.sh [options]

Options:
  --tag <tag>        Image tag to deploy. Default: latest-demo
  --owner <owner>    GHCR owner or org. Default: cyson21
  --registry <host>  Registry host. Default: ghcr.io
  --login            Run docker login using ~/.config/stockrush/github-token
  --refresh-env      Replace infra/demo/.env from infra/demo/.env.example
  --skip-pull        Do not pull images before up
  --skip-smoke       Do not run ./scripts/demo-smoke.sh after up
  -h, --help         Show this help
EOF
}

while (($#)); do
  case "$1" in
    --tag)
      image_tag="${2:?--tag requires a value}"
      shift 2
      ;;
    --owner)
      image_owner="${2:?--owner requires a value}"
      shift 2
      ;;
    --registry)
      image_registry="${2:?--registry requires a value}"
      shift 2
      ;;
    --login)
      login=true
      shift
      ;;
    --refresh-env)
      refresh_env=true
      shift
      ;;
    --skip-pull)
      skip_pull=true
      shift
      ;;
    --skip-smoke)
      skip_smoke=true
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

if [[ "$refresh_env" == true ]]; then
  cp "$ENV_EXAMPLE" "$ENV_FILE"
elif [[ ! -f "$ENV_FILE" ]]; then
  cp "$ENV_EXAMPLE" "$ENV_FILE"
fi

if [[ "$login" == true ]]; then
  if [[ ! -s "$TOKEN_FILE" ]]; then
    printf 'Token file not found or empty: %s\n' "$TOKEN_FILE" >&2
    exit 1
  fi
  docker login "$image_registry" -u "$image_owner" --password-stdin < "$TOKEN_FILE"
fi

export STOCKRUSH_IMAGE_REGISTRY="$image_registry"
export STOCKRUSH_IMAGE_OWNER="$image_owner"
export STOCKRUSH_IMAGE_TAG="$image_tag"

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$IMAGE_COMPOSE_FILE" "$@"
}

verify_pull_permission() {
  local probe_image="$image_registry/$image_owner/stockrush-gateway:$image_tag"

  if docker manifest inspect "$probe_image" >/dev/null 2>&1; then
    return
  fi

  printf 'Cannot read GHCR image manifest: %s\n' "$probe_image" >&2
  printf 'Check that the token has read:packages permission or that the package is public.\n' >&2
  exit 1
}

printf '[deploy] registry=%s owner=%s tag=%s\n' "$image_registry" "$image_owner" "$image_tag"

if [[ "$skip_pull" != true ]]; then
  verify_pull_permission
  compose pull
fi

compose up -d --no-build
compose ps

if [[ "$skip_smoke" != true ]]; then
  "$ROOT_DIR/scripts/demo-smoke.sh"
fi
