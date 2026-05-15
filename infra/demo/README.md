# StockRush Demo Runtime

`infra/demo` runs the portable full-stack demo runtime. It is separate from `infra/local`, which stays optimized for host JVM/Node debugging.

## Included Stack

| Area | Containers |
|---|---|
| Infrastructure | PostgreSQL, Redis, Apache Kafka, Kafka UI, Keycloak |
| Backend | Gateway, Catalog, Inventory, Order, Payment, Promotion, Fulfillment, Read Model |
| Web apps | Customer App, Admin App |

The demo stack uses a separate high host-port range so it can run next to the developer runtime on the same machine.

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
| Catalog Service | `http://localhost:28081` |
| Inventory Service | `http://localhost:28082` |
| Order Service | `http://localhost:28083` |
| Payment Service | `http://localhost:28084` |
| Promotion Service | `http://localhost:28085` |
| Fulfillment Service | `http://localhost:28086` |
| Read Model Service | `http://localhost:28087` |
| Keycloak | `http://localhost:28088` |
| Kafka UI | `http://localhost:29090` |

## Environment

The wrapper scripts copy `infra/demo/.env.example` to `infra/demo/.env` on first run. Override host ports there when another local process already uses a default port.

If `infra/demo/.env` already exists, later `.env.example` changes are not copied automatically. Run `./scripts/demo-up.sh --refresh-env` or `.\scripts\demo-up.ps1 --refresh-env` to replace it with the current demo defaults.

`demo-up` checks host ports before starting containers. If a port is already listening, edit the matching value in `infra/demo/.env`, then run `demo-up` again. Use `--skip-port-check` only when the current demo stack is already running and you intentionally want Docker Compose to reconcile it.

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

Keycloak demo setup:

- Realm import file: `infra/demo/keycloak/stockrush-realm.json`
- Demo credentials and endpoints are controlled by `infra/demo/.env.example`

## Web App Routing

The web app containers serve Vite build output through Nginx. Nginx also proxies the existing web app service prefixes:

- `/catalog` -> Catalog Service
- `/inventory` -> Inventory Service
- `/orders` -> Order Service
- `/payment` -> Payment Service
- `/promotion` -> Promotion Service
- `/api/admin/outbox-services`, `/api/admin/coupon-usages`, `/api/admin/fulfillment-requests`, and `/api/read-model` -> Gateway

## Current Smoke Coverage

`demo-smoke` checks service Actuator `health`, `info`, and `metrics`, web app roots, direct Catalog/Inventory read endpoints, Gateway Read Model routing with admin bearer token, the `demo-order-flow` E2E runner, and the high-volume `burst-idempotency` runner. Use `--skip-burst` for a quicker local check when the burst scenario is not needed.

The smoke flow now requests fresh admin/customer tokens from Keycloak before local-e2e calls, then passes `--admin-bearer-token` to protected admin commands.

The order-flow runner seeds a unique demo product/SKU and coupon, verifies coupon quote through Gateway, creates `CARD`, `FAIL_CARD`, and `DELAY_CARD` orders through Gateway, cancels the delayed order through the admin API, relays service outboxes, and checks final order/stock/outbox state. The `CARD` order must keep the expected `couponCode`, `discountAmount`, and `payableAmount`.

Latest local build verification: after rebuilding the demo stack, `./scripts/demo-smoke.sh` passed for product `DEMO-E2E-20260514200918-a2fced8f` and burst product `BURST-E2E-20260514200937-7717b3c7`. The smoke confirmed all service Actuator `health/info/metrics` endpoints, `CARD` coupon discount `1000`, final demo stock `available=19/reserved=0`, burst convergence `confirmed=4/cancelled=8`, and `pendingOutboxDelta=0` for Order, Inventory, and Payment.

Latest GHCR deploy verification: after publishing multi-platform `latest-demo` images from `58ce878c5ac3`, `./scripts/deploy-local.sh --login --tag latest-demo` passed on Apple Silicon Mac. The smoke used product `DEMO-E2E-20260515095202-eb551849` and burst product `BURST-E2E-20260515095217-287c5a2a`, confirmed all service Actuator `health/info/metrics` endpoints, `CARD` coupon discount `1000`, final demo stock `available=19/reserved=0`, burst convergence `confirmed=4/cancelled=8`, and `pendingOutboxDelta=0` for Order, Inventory, and Payment.
