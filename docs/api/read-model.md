# Read Model API

Read Model API는 주문 lifecycle event로 만든 projection에서 고객 주문 내역과 관리자 주문 요약을 조회하는 데 사용한다.

공통 응답과 헤더 규칙은 [Common API Response](common.md)를 따른다.

Base URLs:

- Public Gateway route: `http://localhost:18080`
- Service-local debug route: `http://localhost:18087`

Mobile and external demo clients call the Gateway route. Service-local access is for controlled internal checks only; customer identity must come from the Gateway-issued trusted subject header.

## Customer Order History

`GET /api/read-model/orders`

### Headers

| Header | Required | Description |
|---|---:|---|
| `Authorization` | yes at Gateway | Bearer token used by Gateway to derive `X-StockRush-Subject` |
| `X-Correlation-Id` | no | HTTP 흐름 추적 기준 |
| `X-StockRush-Subject` | internal | Gateway forwarded subject; clients must not provide or override it |

### Query Parameters

| Parameter | Required | Rule |
|---|---:|---|
| `orderId` | no | exact order id filter within the member history |
| `status` | no | filters by order status such as `CREATED`, `CONFIRMED`, `CANCELLED` |
| `sagaStatus` | no | filters by Saga state such as `STARTED`, `COMPLETED`, `FAILED`, `PAYMENT_DELAYED` |
| `couponCode` | no | exact coupon code filter |
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
Customer member id is derived from the trusted subject. Query `memberId` is not a trusted customer input and is ignored on the customer route. When optional filters are omitted or blank after trimming, the API returns the trusted subject's default history page.

Missing trusted customer identity returns `401 Unauthorized` with code `READ_MODEL_TRUSTED_IDENTITY_REQUIRED`.

## Admin Order Summary

`GET /api/read-model/admin/orders`

### Headers

| Header | Required | Description |
|---|---:|---|
| `X-Correlation-Id` | no | HTTP 흐름 추적 기준 |

### Query Parameters

| Parameter | Required | Rule |
|---|---:|---|
| `orderId` | no | exact order id filter |
| `memberId` | no | exact member id filter |
| `status` | no | filters by order status such as `CREATED`, `CONFIRMED`, `CANCELLED` |
| `sagaStatus` | no | filters by Saga state such as `STARTED`, `COMPLETED`, `FAILED`, `PAYMENT_DELAYED` |
| `couponCode` | no | exact coupon code filter |
| `page` | no | default `0`; negative values are normalized to `0` |
| `size` | no | default `20`; values over `100` are normalized to `100` |

### Response

`200 OK`

The response body uses the same page and item shape as customer order history.
Admin search can combine `orderId`, `memberId`, `status`, `sagaStatus`, and `couponCode`; blank optional values are ignored.

## Projection Rules

| Source event | Result |
|---|---|
| `OrderCreated` | Inserts summary with `CREATED` / `STARTED` when absent |
| `OrderConfirmed` | Updates summary to `CONFIRMED` / `COMPLETED` |
| `OrderCancelled` | Updates summary to `CANCELLED` / `FAILED` and stores `cancellationReason` |

Result events require an existing summary. If the summary is not ready, the consumer rolls back the processed marker and relies on Kafka retry.
