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

## Admin API

The admin request history API is available through Gateway and the service-local route with the same path.

`GET /api/admin/fulfillment-requests?orderId=ord_001&status=PREPARING&page=0&size=20`

Query parameters are optional. `page` is normalized to `0` or greater, and `size` is capped at `100`.

Response:

```json
{
  "success": true,
  "data": {
    "page": 0,
    "size": 20,
    "items": [
      {
        "requestId": "018f8d0b-8d32-7c42-9f1b-78328e0f0702",
        "orderId": "ord_fulfillment_admin_002",
        "status": "PREPARING",
        "requestedAt": "2026-05-13T08:12:00Z",
        "sourceEventId": "018f8d0b-8d32-7c42-9f1b-78328e0f07a2",
        "correlationId": "corr-fulfillment-admin-002",
        "idempotencyKey": "idem-fulfillment-admin-002",
        "createdAt": "2026-05-13T08:12:00Z",
        "updatedAt": "2026-05-13T08:12:00Z"
      }
    ]
  },
  "error": null,
  "trace": {
    "correlationId": "corr-fulfillment-list"
  }
}
```

## State

| Table | Purpose |
|---|---|
| `fulfillment_requests` | Shipment preparation request per confirmed order |
| `processed_events` | Consumer duplicate handling by event id and consumer group |

This slice stops at shipment preparation visibility. Carrier assignment, labels, and tracking are later states.
