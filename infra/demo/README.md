# StockRush Demo Runtime

`infra/demo` runs the portable full-stack demo runtime. It is separate from `infra/local`, which stays optimized for host JVM/Node debugging.

## Included Stack

| Area | Containers |
|---|---|
| Infrastructure | PostgreSQL, Redis, Apache Kafka, Kafka UI |
| Backend | Gateway, Catalog, Inventory, Order, Payment, Promotion, Fulfillment, Read Model |
| Web apps | Customer App, Admin App |

The demo stack keeps the same host ports as the developer runtime so existing runbooks and mobile API base URLs stay familiar.

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
| Customer App | `http://localhost:5173` |
| Admin App | `http://localhost:5174` |
| Gateway | `http://localhost:18080` |
| Catalog Service | `http://localhost:18081` |
| Inventory Service | `http://localhost:18082` |
| Order Service | `http://localhost:18083` |
| Payment Service | `http://localhost:18084` |
| Promotion Service | `http://localhost:18085` |
| Fulfillment Service | `http://localhost:18086` |
| Read Model Service | `http://localhost:18087` |
| Kafka UI | `http://localhost:19090` |

## Environment

The wrapper scripts copy `infra/demo/.env.example` to `infra/demo/.env` on first run. Override host ports there when another local process already uses a default port.

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

`demo-smoke` checks service health, web app roots, direct Catalog/Inventory read endpoints, and Gateway Read Model routing. Full order-flow seeding is the next Phase 5 slice.
