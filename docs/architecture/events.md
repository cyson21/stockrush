# Event Envelope

Kafka events use a common envelope with event-specific payloads.

## Envelope

```json
{
  "eventId": "018f8d0b-8d32-7c42-9f1b-78328e0f7a11",
  "eventType": "OrderCreated",
  "eventVersion": 1,
  "aggregateType": "order",
  "aggregateId": "ord_20260512_000001",
  "correlationId": "018f8d0b-8d32-7c42-9f1b-78328e0f7a10",
  "causationId": null,
  "idempotencyKey": "client-key-001",
  "occurredAt": "2026-05-12T09:30:00Z",
  "sourceService": "order-service",
  "payload": {}
}
```

## Required Fields

| Field | Purpose |
|---|---|
| `eventId` | Unique event identifier |
| `eventType` | Event name |
| `eventVersion` | Payload version |
| `aggregateId` | Primary aggregate identifier |
| `correlationId` | End-to-end trace key |
| `idempotencyKey` | Duplicate handling key |
| `occurredAt` | Event occurrence timestamp |
| `payload` | Event-specific body |

## Additional Phase 1 Fields

| Field | Purpose |
|---|---|
| `aggregateType` | Aggregate category for operations/search |
| `causationId` | Previous event ID when this event was caused by another event |
| `sourceService` | Publishing service |

## Phase 1 Event Set

| Event | Topic | Producer | Consumer |
|---|---|---|---|
| `OrderCreated` | `stockrush.order.events.v1` | order-service | inventory-service |
| `InventoryReserved` | `stockrush.inventory.events.v1` | inventory-service | order-service |
| `InventoryReservationFailed` | `stockrush.inventory.events.v1` | inventory-service | order-service |
| `PaymentAuthorizationRequested` | `stockrush.payment.commands.v1` | order-service | payment-service |
| `PaymentAuthorized` | `stockrush.payment.events.v1` | payment-service | order-service |
| `PaymentAuthorizationFailed` | `stockrush.payment.events.v1` | payment-service | order-service |
| `PaymentAuthorizationDelayed` | `stockrush.payment.events.v1` | payment-service | order-service |
| `OrderConfirmed` | `stockrush.order.events.v1` | order-service | inventory-service, read models |
| `OrderCancelled` | `stockrush.order.events.v1` | order-service | inventory-service, read models |
| `InventoryReservationConfirmed` | `stockrush.inventory.events.v1` | inventory-service | operations/read models |
| `InventoryReservationReleased` | `stockrush.inventory.events.v1` | inventory-service | operations/read models |

## Payload Rules

- Payloads include only the event-specific data needed by consumers.
- Consumers must not query another service schema to enrich events.
- Business failures are represented as failure events.
- Technical failures are handled by retry topics and DLQ.

## Phase 1 Payment Payload Notes

| Event | Required payload fields |
|---|---|
| `PaymentAuthorizationRequested` | `orderId`, `amount`, `method` |
| `PaymentAuthorized` | `paymentId`, `orderId`, `amount`, `method`, `authorizedAt` |
| `PaymentAuthorizationFailed` | `orderId`, `amount`, `method`, `reason`, `failedAt` |
| `PaymentAuthorizationDelayed` | `orderId`, `amount`, `method`, `reason`, `delayedAt` |

`PaymentAuthorizationRequested.method` is stored from the order request. If omitted, Order Service uses `CARD`. The `FAIL_CARD` method is reserved for deterministic local failure simulation and produces `PaymentAuthorizationFailed` with reason `PAYMENT_DECLINED`.
The `DELAY_CARD` method is reserved for deterministic local delay simulation and produces `PaymentAuthorizationDelayed` with reason `PAYMENT_DELAYED`.

## Phase 1 Inventory Finalization Notes

| Source event | Inventory action | Published event |
|---|---|---|
| `OrderConfirmed` | Move reservation from `RESERVED` to `CONFIRMED`, decrement `reserved_quantity` | `InventoryReservationConfirmed` |
| `OrderCancelled` | Move reservation from `RESERVED` to `RELEASED`, restore `available_quantity`, decrement `reserved_quantity` | `InventoryReservationReleased` |

Inventory Service consumes order result events from `stockrush.order.events.v1` and keeps all stock mutations inside the `inventory` schema.
