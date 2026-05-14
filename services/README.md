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
| promotion-service | 18085 | promotion | coupon definition, quote API, and usage lifecycle |
| fulfillment-service | 18086 | fulfillment | shipment preparation request |
| read-model-service | 18087 | read_model | order summary projection |

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
cd services/promotion-service && PROMOTION_KAFKA_LISTENERS_ENABLED=true mvn spring-boot:run
cd services/fulfillment-service && FULFILLMENT_KAFKA_LISTENERS_ENABLED=true mvn spring-boot:run
cd services/read-model-service && READ_MODEL_KAFKA_LISTENERS_ENABLED=true mvn spring-boot:run
```

Promotion Service is currently service-local and is not proxied by Gateway. Order Service calls it through `PROMOTION_SERVICE_URL`, Customer App local development reaches it through the `/promotion` Vite proxy, and Kafka listeners consume order events for coupon usage state.
Fulfillment Service is currently event-only and is not proxied by Gateway.
Read Model Service is currently service-local and is not proxied by Gateway. It consumes order lifecycle events and exposes projection-backed order summary APIs.

First run may download Maven dependencies.
