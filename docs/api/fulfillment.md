# Fulfillment API

Fulfillment API exposes shipment preparation request history created from successful order events.

Base URLs:

- Gateway admin route: `http://localhost:18080`
- Service-local route: `http://localhost:18086`

## Admin: List Fulfillment Requests

`GET /api/admin/fulfillment-requests?orderId=ord_001&status=PREPARING&page=0&size=20`

Query parameters are optional.

| Parameter | Description |
|---|---|
| `orderId` | exact order id filter |
| `status` | fulfillment request status, currently `PREPARING` |
| `page` | normalized to `0` or greater |
| `size` | default `20`, capped at `100` |

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

## Order Event Consumer

Fulfillment Service consumes `stockrush.order.events.v1` when `FULFILLMENT_KAFKA_LISTENERS_ENABLED=true`.

| Event | Action |
|---|---|
| `OrderConfirmed` | create one `PREPARING` fulfillment request for the order |

Duplicate event delivery is handled by the Fulfillment `processed_events` table with `(event_id, consumer_group)` as the unique key.
