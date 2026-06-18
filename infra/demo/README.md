# StockRush Demo Runtime

`infra/demo` runs the portable full-stack demo runtime. It is separate from `infra/local`, which stays optimized for host JVM/Node debugging.

## Included Stack

| Area | Containers |
|---|---|
| Infrastructure | PostgreSQL, Redis, Apache Kafka, Kafka UI, Keycloak |
| Backend | Gateway, Catalog, Inventory, Order, Payment, Promotion, Fulfillment, Read Model |
| Web apps | Customer App, Admin App |

The demo stack publishes only the Gateway, web apps, Keycloak, and required infrastructure ports. Backend services stay on the Docker network behind Gateway so the host public surface matches the deployed shape more closely.

## macOS / Linux

```bash
./scripts/demo-up.sh
./scripts/demo-smoke.sh
./scripts/demo-down.sh
```

GHCR에 발행된 이미지를 그대로 배포할 때는 local build 대신 deploy wrapper를 사용한다.

```bash
./scripts/deploy-local.sh --login --tag latest-demo
```

## Windows 11

Use Docker Desktop with WSL2 integration enabled. From PowerShell:

```powershell
.\scripts\demo-up.ps1
.\scripts\demo-smoke.ps1
.\scripts\demo-down.ps1
```

GHCR 이미지 배포:

```powershell
.\scripts\deploy-local.ps1 --login --tag latest-demo
```

WSL2 shell users can use the macOS/Linux shell scripts.

## URLs

| Target | URL |
|---|---|
| Customer App | `http://localhost:15173` |
| Admin App | `http://localhost:15174` |
| Gateway | `http://localhost:28080` |
| Keycloak | `http://localhost:28088` |
| Kafka UI | `http://localhost:29090` |

## Environment

The wrapper scripts copy `infra/demo/.env.example` to `infra/demo/.env` on first run. Override host ports there when another local process already uses a default port.

If `infra/demo/.env` already exists, later `.env.example` changes are not copied automatically. Run `./scripts/demo-up.sh --refresh-env` or `.\scripts\demo-up.ps1 --refresh-env` to replace it with the current demo defaults.

`demo-up` checks published host ports before starting containers. If a port is already listening, edit the matching value in `infra/demo/.env`, then run `demo-up` again. Use `--skip-port-check` only when the current demo stack is already running and you intentionally want Docker Compose to reconcile it.

`deploy-local` uses:

- `STOCKRUSH_IMAGE_REGISTRY` default `ghcr.io`
- `STOCKRUSH_IMAGE_OWNER` default `cyson21`
- `STOCKRUSH_IMAGE_TAG` default `latest-demo`

The image override file is `infra/demo/docker-compose.images.yml`.

Inside the Docker network, services use:

- PostgreSQL: `postgres:5432`
- Kafka: `kafka:19092`
- Keycloak: `keycloak:8080`
- Gateway upstream URLs: Docker service names such as `http://order-service:18083`
- Backend service ports: exposed only inside the Docker network; they are not published to the host.

Keycloak demo setup:

- Realm import file: `infra/demo/keycloak/stockrush-realm.json`
- Demo credentials and endpoints are controlled by `infra/demo/.env.example`

## Web App Routing

The web app containers serve Vite build output through Nginx. Nginx proxies Gateway-owned API prefixes only:

- `/api/products` -> Gateway
- `/api/stocks` -> Gateway
- `/api/coupons` -> Gateway
- `/api/orders` -> Gateway
- `/api/admin` -> Gateway
- `/api/read-model` -> Gateway

## Current Smoke Coverage

`demo-smoke` checks Gateway Actuator `health`, `info`, and `metrics`, web app roots, Gateway public product/stock endpoints, Gateway Read Model routing with admin bearer token, the `demo-order-flow` E2E runner, and the high-volume `burst-idempotency` runner. Use `--skip-burst` for a quicker local check when the burst scenario is not needed.

The smoke flow requests fresh admin/customer tokens from Keycloak before local-e2e calls, then passes Gateway `--public-api-url`, `--admin-api-url`, `--order-api-url`, `--admin-bearer-token`, and `--customer-bearer-token` to the runner.

The order-flow runner seeds a unique demo product/SKU and coupon through Gateway admin APIs, verifies coupon quote through Gateway, creates `CARD`, `FAIL_CARD`, and `DELAY_CARD` orders through Gateway, cancels the delayed order through the admin API, relays service outboxes, and checks final order/stock/outbox state. The `CARD` order must keep the expected `couponCode`, `discountAmount`, and `payableAmount`.

Latest local build verification: after rebuilding the demo stack, `./scripts/demo-smoke.sh` passed for product `DEMO-E2E-20260514200918-a2fced8f` and burst product `BURST-E2E-20260514200937-7717b3c7`. That verification predates the Gateway-boundary hardening in this document; rerun `./scripts/demo-smoke.sh` after refreshing `infra/demo/.env` to capture a current runtime proof.

Latest GHCR deploy verification: after publishing multi-platform `latest-demo` images from `58ce878c5ac3`, `./scripts/deploy-local.sh --login --tag latest-demo` passed on Apple Silicon Mac. That verification also predates the Gateway-boundary hardening; refresh the demo env template before relying on those images for a current smoke result.
