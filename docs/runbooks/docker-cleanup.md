# Docker Cleanup Runbook

Use this runbook to keep a demo machine focused on the current StockRush runtime.

## Default Target

Keep:

- `stockrush-demo` containers that run the portable demo stack.
- GHCR `latest-demo` images used by the running demo.
- PostgreSQL and Redis volumes, unless the goal is to reset local data.

Clean:

- duplicate `infra/local` containers such as `stockrush-postgres`, `stockrush-kafka`, and `stockrush-kafka-ui`
- completed init containers
- old local build images named `stockrush-demo-*:latest`
- optional dangling images and build cache

Unrelated containers such as local MySQL are not part of this cleanup.

## Dry Run

```bash
./scripts/docker-clean.sh
```

## Apply Safe Cleanup

```bash
./scripts/docker-clean.sh --apply
```

This keeps the demo stack running and preserves volumes.

## Full Demo Reset

```bash
./scripts/docker-clean.sh --apply --include-demo --include-volumes --prune-dangling-images --prune-build-cache
```

Use this only when local demo data can be discarded.

## Verify

```bash
docker compose --env-file infra/demo/.env -f infra/demo/docker-compose.yml ps
curl -fsS http://localhost:28080/actuator/health
```
