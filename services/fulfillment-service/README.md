# Fulfillment Service

Port: `18086`<br>
Schema: `fulfillment`

Owns fulfillment request state created from completed orders.

## Event Handling

When `FULFILLMENT_KAFKA_LISTENERS_ENABLED=true`, the service consumes `stockrush.order.events.v1`.

| Event | Action |
|---|---|
| `OrderConfirmed` | Create one `PREPARING` fulfillment request for the order |

Duplicate deliveries are recorded in `processed_events` and do not create duplicate fulfillment requests.

## State

| Table | Purpose |
|---|---|
| `fulfillment_requests` | Shipment preparation request per confirmed order |
| `processed_events` | Consumer duplicate handling by event id and consumer group |

This first slice stops at shipment preparation. Carrier assignment, labels, and tracking are later states.
