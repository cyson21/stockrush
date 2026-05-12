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

## Local Java

Use Java 17.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

## Service Run Shape

```bash
cd services/order-service
mvn spring-boot:run
```

First run may download Maven dependencies.

