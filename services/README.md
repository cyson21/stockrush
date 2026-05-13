# Services

StockRush uses independent Spring Boot projects under `services/<service-name>`.

## Phase 1 Services

| Service | Port | Schema | Role |
|---|---:|---|---|
| gateway | 18080 | none | entry point and routing baseline |
| catalog-service | 18081 | catalog | product snapshot source |
| inventory-service | 18082 | inventory | stock reservation |
| order-service | 18083 | orders | order API and Saga Orchestrator |
| payment-service | 18084 | payment | payment simulation |
| promotion-service | 18085 | promotion | coupon definition and quote API |

## Local Java

Use Java 17.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

## Service Run Shape

```bash
cd services/<service-name>
mvn spring-boot:run
```

Event-consuming services need Kafka listeners enabled for local E2E flows.

```bash
cd services/inventory-service && STOCKRUSH_KAFKA_LISTENERS_ENABLED=true mvn spring-boot:run
cd services/order-service && STOCKRUSH_KAFKA_LISTENERS_ENABLED=true mvn spring-boot:run
cd services/payment-service && STOCKRUSH_KAFKA_LISTENERS_ENABLED=true mvn spring-boot:run
```

Promotion Service is currently service-local and is not proxied by Gateway.

First run may download Maven dependencies.
