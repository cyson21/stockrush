# Local Infra

This directory defines the local StockRush infrastructure for developer runtime.

`infra/local` intentionally runs infrastructure only. Spring Boot services, React/Vite web apps, and the Expo mobile app run on the host runtime for faster debugging.

Use [`infra/demo`](../demo/README.md) when you want the portable full-stack runtime that starts infrastructure, backend services, and web apps together with Docker Compose.

## Services

| Name | URL |
|---|---|
| PostgreSQL | `localhost:15432` |
| Redis | `localhost:16379` |
| Kafka | `localhost:19092` |
| Kafka UI | `http://localhost:19090` |

## Commands

```bash
cd infra/local
cp .env.example .env
docker compose config
docker compose up -d
```

`docker compose up -d` may pull images the first time it runs.

The same commands work from macOS, Linux, or Windows 11 WSL2. On native Windows PowerShell, run them from the repository path mounted by Docker Desktop or use WSL2 for path consistency.

## Database

The single local PostgreSQL instance is split by schema.

| Schema | Owner Service |
|---|---|
| `catalog` | catalog-service |
| `inventory` | inventory-service |
| `orders` | order-service |
| `payment` | payment-service |
| `auth` | future auth-service |
| `promotion` | promotion-service |
| `fulfillment` | fulfillment-service |
| `read_model` | read-model-service |

## Kafka

The Kafka container uses Apache Kafka KRaft single-node mode. Internal Docker clients use `kafka:19092`; host clients use `localhost:19092`.

Topic creation is handled by `kafka/topics.sh`.
