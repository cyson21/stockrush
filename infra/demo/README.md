# StockRush Demo Runtime

`infra/demo` runs the portable full-stack demo runtime. It is separate from `infra/local`, which stays optimized for host JVM/Node debugging.

## Included Stack

| Area | Containers |
|---|---|
| Infrastructure | PostgreSQL, Redis, Apache Kafka, Kafka UI |
| Backend | Gateway, Catalog, Inventory, Order, Payment, Promotion, Fulfillment, Read Model |
| Web apps | Customer App, Admin App |

The demo stack uses a separate high host-port range so it can run next to the developer runtime on the same machine.

## macOS / Linux

```bash
./scripts/demo-up.sh
./scripts/demo-smoke.sh
./scripts/demo-down.sh
```

## Windows 11

Use Docker Desktop with WSL2 integration enabled. From PowerShell:

```powershell
.\scripts\demo-up.ps1
.\scripts\demo-smoke.ps1
.\scripts\demo-down.ps1
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
| Kafka UI | `http://localhost:29090` |

## Environment

The wrapper scripts copy `infra/demo/.env.example` to `infra/demo/.env` on first run. Override host ports there when another local process already uses a default port.

If `infra/demo/.env` already exists, later `.env.example` changes are not copied automatically. Run `./scripts/demo-up.sh --refresh-env` or `.\scripts\demo-up.ps1 --refresh-env` to replace it with the current demo defaults.

`demo-up` checks host ports before starting containers. If a port is already listening, edit the matching value in `infra/demo/.env`, then run `demo-up` again. Use `--skip-port-check` only when the current demo stack is already running and you intentionally want Docker Compose to reconcile it.

Inside the Docker network, services use:

- PostgreSQL: `postgres:5432`
- Kafka: `kafka:19092`
- Gateway upstream URLs: Docker service names such as `http://order-service:18083`

## Web App Routing

The web app containers serve Vite build output through Nginx. Nginx also proxies the existing web app service prefixes:

- `/catalog` -> Catalog Service
- `/inventory` -> Inventory Service
- `/orders` -> Order Service
- `/payment` -> Payment Service
- `/promotion` -> Promotion Service
- `/api/admin/outbox-services` and `/api/read-model` -> Gateway

## Current Smoke Coverage

`demo-smoke` checks service health, web app roots, direct Catalog/Inventory read endpoints, Gateway Read Model routing, and the `demo-order-flow` E2E runner.

The order-flow runner seeds a unique demo product/SKU, creates `CARD`, `FAIL_CARD`, and `DELAY_CARD` orders through Gateway, cancels the delayed order through the admin API, relays service outboxes, and checks final order/stock/outbox state.

Latest local verification: `./scripts/demo-up.sh` started the full stack on the demo port range, then `./scripts/demo-smoke.sh` passed for product `DEMO-E2E-20260514155005-f0f4735a` with final stock `available=19`, `reserved=0`, and `pendingOutboxDelta=0` for Order, Inventory, and Payment.
