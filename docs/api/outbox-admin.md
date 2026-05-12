# Outbox Admin API

Outbox Admin API는 각 서비스가 자기 schema 안의 `outbox_events`를 조회하고, due 상태의 `PENDING` 이벤트 발행을 수동으로 트리거하는 운영 API이다.

## Service Endpoints

| Service | Base URL |
|---|---|
| order-service | `/api/admin/outbox-events` |
| inventory-service | `/api/admin/outbox-events` |
| payment-service | `/api/admin/outbox-events` |

각 서비스는 동일한 path를 사용하지만 자기 DB schema만 조회한다. 관리자 앱은 서비스별 응답을 병합해서 보여준다.

## List Outbox Events

`GET /api/admin/outbox-events`

### Query Parameters

| Name | Required | Default | Description |
|---|---:|---|---|
| `status` | no | `PENDING,FAILED` | comma-separated outbox status list |
| `limit` | no | `50` | page size |
| `offset` | no | `0` | row offset |

### Response Data

```json
{
  "limit": 50,
  "offset": 0,
  "items": [
    {
      "eventId": "018f8d0b-8d32-7c42-9f1b-78328e0f7b02",
      "aggregateType": "order",
      "aggregateId": "ord_20260513_001",
      "eventType": "OrderCancelled",
      "topic": "stockrush.order.events.v1",
      "partitionKey": "ord_20260513_001",
      "payload": "{\"orderId\":\"ord_20260513_001\"}",
      "status": "FAILED",
      "retryCount": 5,
      "maxRetryCount": 5,
      "nextRetryAt": null,
      "errorMessage": "kafka unavailable",
      "createdAt": "2026-05-13T03:00:00Z",
      "publishedAt": null
    }
  ]
}
```

## Retry Pending Events

`POST /api/admin/outbox-events/retry`

### Query Parameters

| Name | Required | Default | Description |
|---|---:|---|---|
| `batchSize` | no | `10` | max pending rows to claim and publish |

### Response Data

```json
{
  "claimed": 1,
  "published": 1,
  "failed": 0
}
```

## Rules

- Retry only claims `PENDING` events whose `next_retry_at` is null or due.
- Each service publishes through its existing relay service.
- Manual `FAILED -> PENDING` state change is not part of this slice.
- Authentication is still out of scope and must be added before public deployment.
