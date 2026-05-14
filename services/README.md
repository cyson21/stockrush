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

macOS:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

Windows 11 PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

Windows 11 WSL2:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

Adjust the paths to the installed JDK location. The portable demo runtime avoids host Java setup by running backend services in containers.

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

Promotion Service is proxied by Gateway for coupon quote and admin coupon usage history. It is also reachable through the Customer App `/promotion` development proxy. Order Service calls it through `PROMOTION_SERVICE_URL`, and Kafka listeners consume order events for coupon usage state.
Fulfillment Service is proxied by Gateway for admin fulfillment request history and consumes order events for shipment preparation state.
Read Model Service is proxied by Gateway for customer/admin order summaries. It consumes order lifecycle events and exposes projection-backed order summary APIs.

For a Docker-based full-stack run, use [`../infra/demo`](../infra/demo/README.md).

First run may download Maven dependencies.
