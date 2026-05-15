# Outbox Admin API

Outbox Admin API는 각 서비스가 자기 schema 안의 `outbox_events`를 조회하고, due 상태의 `PENDING` 이벤트 발행과 `FAILED` 이벤트 재처리 준비를 수동으로 트리거하는 운영 API이다.

## Service Endpoints

Gateway exposes one admin route family and maps the `service` path segment to the selected service-local endpoint.

| Operation | Gateway path | Upstream path |
|---|---|---|
| List | `GET /api/admin/outbox-services/{service}/events` | `GET /api/admin/outbox-events` |
| Retry | `POST /api/admin/outbox-services/{service}/events/retry` | `POST /api/admin/outbox-events/retry` |
| Requeue failed | `POST /api/admin/outbox-services/{service}/events/failed/requeue` | `POST /api/admin/outbox-events/failed/requeue` |

Allowed `service` values: `order`, `inventory`, `payment`.

각 서비스는 동일한 upstream path를 사용하지만 자기 DB schema만 조회한다. 관리자 앱은 Gateway path에 service 값을 넣어 서비스별 응답을 보여준다.

## List Outbox Events

`GET /api/admin/outbox-services/{service}/events`

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

`POST /api/admin/outbox-services/{service}/events/retry`

### Headers

| Name | Required | Description |
|---|---:|---|
| `X-Correlation-Id` | no | trace id echoed by Gateway and upstream service |
| `X-Operator-Id` | Gateway-derived | Gateway overwrites this from the authenticated admin token; direct service calls may omit it and default to `unknown` |

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

## Requeue Failed Events

`POST /api/admin/outbox-services/{service}/events/failed/requeue`

### Headers

| Name | Required | Description |
|---|---:|---|
| `X-Correlation-Id` | no | trace id echoed by Gateway and upstream service |
| `X-Operator-Id` | Gateway-derived | Gateway overwrites this from the authenticated admin token; direct service calls may omit it and default to `unknown` |

### Query Parameters

| Name | Required | Default | Description |
|---|---:|---|---|
| `batchSize` | no | `10` | max failed rows to move back to pending |

### Response Data

```json
{
  "updated": 1
}
```

### Behavior

- Selects only `FAILED` rows.
- Moves selected rows to `PENDING`.
- Resets `retryCount` to `0`.
- Clears `nextRetryAt` and `errorMessage`.
- Keeps `maxRetryCount` and `publishedAt` unchanged.
- Existing retry API or relay loop publishes the requeued rows.

## Admin Action Audit

Each producing service stores retry and requeue requests in `outbox_admin_actions`.

| Column | Description |
|---|---|
| `action` | `RETRY_PENDING` or `REQUEUE_FAILED` |
| `requested_batch_size` | requested batch size |
| `affected_count` | claimed or updated row count |
| `operator_id` | Gateway-derived admin principal, or `unknown` for direct service-local calls without the header |
| `correlation_id` | resolved trace id |
| `created_at` | DB timestamp |

## Rules

- Retry only claims `PENDING` events whose `next_retry_at` is null or due.
- Requeue only changes `FAILED` events back to `PENDING`; it does not publish directly.
- Each service publishes through its existing relay service.
- Retry and requeue actions write an audit row after the service action succeeds.
- Gateway rejects unknown service values before calling an upstream service.
- Gateway-protected demo routes require an admin bearer token.

## Local Recovery Runner

로컬 운영 복구 점검은 Gateway route를 직접 호출하는 대신 runner를 우선 사용한다.

```bash
./tools/local-e2e/local-e2e outbox-recovery \
  --operator-id local-runbook \
  --max-attempts 3 \
  --wait-seconds 1
```

Runner 기준:

- `order`, `inventory`, `payment` 각각 `PENDING,FAILED` row를 조회한다.
- 기본값은 `FAILED` requeue 후 `PENDING` retry를 실행한다.
- `nextRetryAt`이 미래인 `PENDING` row는 deferred로 분류한다.
- 성공 기준은 최종 `retryablePendingCounts`와 `failedCounts`가 모두 0인 상태다.
