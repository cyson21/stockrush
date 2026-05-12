# Kafka Topics

## Naming

Topic names follow this shape:

```text
stockrush.<domain>.<message-kind>.v<version>
stockrush.<domain>.<message-kind>.v<version>.retry.<delay>
stockrush.<domain>.<message-kind>.v<version>.dlq
```

`orderId` is the default Kafka key for the first order Saga flow. This keeps all events for the same order in the same partition.

## Initial Topics

| Topic | Key | Producer | Consumer |
|---|---|---|---|
| `stockrush.order.events.v1` | `orderId` | order-service | inventory-service, payment-service, read models |
| `stockrush.inventory.commands.v1` | `orderId` | order-service | inventory-service |
| `stockrush.inventory.events.v1` | `orderId` | inventory-service | order-service |
| `stockrush.payment.commands.v1` | `orderId` | order-service | payment-service |
| `stockrush.payment.events.v1` | `orderId` | payment-service | order-service |
| `stockrush.order.events.v1.retry.1m` | original key | retry handler | original consumer group |
| `stockrush.order.events.v1.retry.5m` | original key | retry handler | original consumer group |
| `stockrush.order.events.v1.dlq` | original key | retry handler | admin/ops reader |
| `stockrush.inventory.events.v1.retry.1m` | original key | retry handler | original consumer group |
| `stockrush.inventory.events.v1.retry.5m` | original key | retry handler | original consumer group |
| `stockrush.inventory.events.v1.dlq` | original key | retry handler | admin/ops reader |
| `stockrush.payment.events.v1.retry.1m` | original key | retry handler | original consumer group |
| `stockrush.payment.events.v1.retry.5m` | original key | retry handler | original consumer group |
| `stockrush.payment.events.v1.dlq` | original key | retry handler | admin/ops reader |

## Retry/DLQ Headers

Retry and DLQ records preserve the original event envelope and add headers.

| Header | Purpose |
|---|---|
| `x-original-topic` | first topic name |
| `x-original-partition` | first partition |
| `x-original-offset` | first offset |
| `x-retry-count` | number of handling attempts |
| `x-error-class` | failing exception type |
| `x-error-message` | short failure reason |
| `x-failed-at` | failure timestamp |

Business failures such as insufficient stock or payment decline are domain events, not retry candidates.

