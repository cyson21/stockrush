# Read Model Service

Read Model Service keeps query-optimized order summaries in the `read_model` schema.

## Runtime

| Item | Value |
|---|---|
| Port | `18087` |
| Schema | `read_model` |
| Kafka topic | `stockrush.order.events.v1` |
| Consumer group | `read-model-service` |
| Listener flag | `READ_MODEL_KAFKA_LISTENERS_ENABLED=true` |

## Event Projection

| Source event | Projection action |
|---|---|
| `OrderCreated` | Insert order summary with member, price snapshot, item count, `CREATED` / `STARTED` when absent |
| `OrderConfirmed` | Mark order summary as `CONFIRMED` / `COMPLETED` |
| `OrderCancelled` | Mark order summary as `CANCELLED` / `FAILED` and store cancellation reason |

The service stores processed event IDs with the `read-model-service` consumer group so replayed messages do not create duplicate side effects.
If a result event arrives before its `OrderCreated` summary is ready, the service raises an error and rolls back the processed marker so Kafka retry can handle it later. A later duplicate `OrderCreated` event does not downgrade a terminal summary.

## APIs

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/read-model/orders?memberId={memberId}` | Customer order history from projection |
| `GET` | `/api/read-model/admin/orders?status={status}` | Admin order summary list from projection |

Both APIs return the common `ApiResponse` shape and propagate `X-Correlation-Id`.

## Local Run

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
READ_MODEL_KAFKA_LISTENERS_ENABLED=true mvn spring-boot:run
```

## Verification

```bash
mvn test
```
