#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/infra/demo/docker-compose.yml"
ENV_FILE="$ROOT_DIR/infra/demo/.env"
ENV_EXAMPLE="$ROOT_DIR/infra/demo/.env.example"

if [[ ! -f "$ENV_FILE" ]]; then
  cp "$ENV_EXAMPLE" "$ENV_FILE"
fi

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --build "$@"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps
