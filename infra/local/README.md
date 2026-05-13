# Local Infra

This directory defines the local StockRush infrastructure.

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
| `read_model` | future read model services |

## Kafka

The Kafka container uses Apache Kafka KRaft single-node mode. Internal Docker clients use `kafka:19092`; host clients use `localhost:19092`.

Topic creation is handled by `kafka/topics.sh`.
