# Read Model API

Read Model API는 주문 lifecycle event로 만든 projection에서 고객 주문 내역과 관리자 주문 요약을 조회하는 데 사용한다.

공통 응답과 헤더 규칙은 [Common API Response](common.md)를 따른다.

Base URLs:

- Gateway route: `http://localhost:18080`
- Service-local direct check: `http://localhost:18087`

Mobile and external demo clients should call the Gateway route.

## Customer Order History

`GET /api/read-model/orders`

### Headers

| Header | Required | Description |
|---|---:|---|
| `X-Correlation-Id` | no | HTTP 흐름 추적 기준 |

### Query Parameters

| Parameter | Required | Rule |
|---|---:|---|
| `memberId` | yes | customer member id |
| `page` | no | default `0`; negative values are normalized to `0` |
| `size` | no | default `20`; values over `100` are normalized to `100` |

### Response

`200 OK`

```json
{
  "success": true,
  "data": {
    "page": 0,
    "size": 20,
    "items": [
      {
        "orderId": "ord_20260514_001",
        "memberId": "member-demo",
        "status": "CONFIRMED",
        "sagaStatus": "COMPLETED",
        "couponCode": "WELCOME10",
        "totalAmount": 12000.00,
        "discountAmount": 2000.00,
        "payableAmount": 10000.00,
        "itemCount": 1,
        "cancellationReason": null,
        "createdAt": "2026-05-14T00:20:00Z",
        "updatedAt": "2026-05-14T00:22:00Z"
      }
    ]
  },
  "trace": {
    "correlationId": "read-model-customer-orders"
  }
}
```

Results are ordered by `createdAt desc`, then projection row id descending.

## Admin Order Summary

`GET /api/read-model/admin/orders`

### Headers

| Header | Required | Description |
|---|---:|---|
| `X-Correlation-Id` | no | HTTP 흐름 추적 기준 |

### Query Parameters

| Parameter | Required | Rule |
|---|---:|---|
| `status` | no | filters by order status such as `CREATED`, `CONFIRMED`, `CANCELLED` |
| `page` | no | default `0`; negative values are normalized to `0` |
| `size` | no | default `20`; values over `100` are normalized to `100` |

### Response

`200 OK`

The response body uses the same page and item shape as customer order history.

## Projection Rules

| Source event | Result |
|---|---|
| `OrderCreated` | Inserts summary with `CREATED` / `STARTED` when absent |
| `OrderConfirmed` | Updates summary to `CONFIRMED` / `COMPLETED` |
| `OrderCancelled` | Updates summary to `CANCELLED` / `FAILED` and stores `cancellationReason` |

Result events require an existing summary. If the summary is not ready, the consumer rolls back the processed marker and relies on Kafka retry.
