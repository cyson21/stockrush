# Customer Order API

Customer Order API는 고객 앱에서 상품과 SKU를 선택한 뒤 주문을 생성하고, 주문 상태와 Saga 상태를 추적하는 데 사용한다.

공통 응답과 헤더 규칙은 [Common API Response](common.md)를 따른다.

## Create Order

`POST /api/orders`

### Headers

| Header | Required | Description |
|---|---:|---|
| `Idempotency-Key` | yes | 중복 주문 생성 요청 방지 기준 |
| `X-Correlation-Id` | no | HTTP/Kafka 흐름 추적 기준 |

### Request

```json
{
  "memberId": "member-demo",
  "paymentMethod": "CARD",
  "items": [
    {
      "productCode": "DEMO-001",
      "skuId": "DEMO-001-S",
      "quantity": 1,
      "unitPrice": 12000.00
    }
  ]
}
```

### Fields

| Field | Required | Rule |
|---|---:|---|
| `memberId` | yes | non-blank |
| `paymentMethod` | no | defaults to `CARD`; blank value is invalid |
| `items` | yes | non-empty |
| `items[].productCode` | yes | non-blank |
| `items[].skuId` | yes | non-blank |
| `items[].quantity` | yes | `>= 1` |
| `items[].unitPrice` | yes | `> 0` |

### Payment Methods

| Method | Purpose |
|---|---|
| `CARD` | successful authorization scenario |
| `FAIL_CARD` | forced payment failure and stock release scenario |
| `DELAY_CARD` | delayed authorization scenario; admin can later request cancellation |

### Response

`201 Created`

Response header:

```text
Location: /api/orders/{orderId}
X-Correlation-Id: {resolvedCorrelationId}
```

Body:

```json
{
  "success": true,
  "data": {
    "orderId": "ord_20260513_001",
    "status": "CREATED",
    "sagaStatus": "STARTED",
    "paymentMethod": "CARD",
    "totalAmount": 12000.00
  },
  "trace": {
    "correlationId": "customer-app-order-create"
  }
}
```

The response means the order row and `OrderCreated` outbox row were stored. Inventory and payment processing continue asynchronously through Kafka.

## Get Order Detail

`GET /api/orders/{orderId}`

### Headers

| Header | Required | Description |
|---|---:|---|
| `X-Correlation-Id` | no | HTTP/Kafka 흐름 추적 기준 |

### Response

`200 OK`

```json
{
  "success": true,
  "data": {
    "orderId": "ord_20260513_001",
    "memberId": "member-demo",
    "status": "CONFIRMED",
    "sagaStatus": "COMPLETED",
    "paymentMethod": "CARD",
    "totalAmount": 12000.00,
    "items": [
      {
        "productCode": "DEMO-001",
        "skuId": "DEMO-001-S",
        "quantity": 1,
        "unitPrice": 12000.00,
        "lineAmount": 12000.00
      }
    ]
  },
  "trace": {
    "correlationId": "customer-app-order-status"
  }
}
```

## Status Values

### Order Status

| Value | Meaning |
|---|---|
| `CREATED` | order was accepted and async processing is running or waiting |
| `CONFIRMED` | inventory and payment completed successfully |
| `CANCELLED` | order was cancelled after inventory or payment failure/cancellation |

### Saga Status

| Value | Meaning |
|---|---|
| `STARTED` | order was created |
| `INVENTORY_REQUESTED` | inventory reservation command/event is in progress |
| `PAYMENT_REQUESTED` | payment authorization command/event is in progress |
| `PAYMENT_DELAYED` | payment is delayed and order remains open |
| `PAYMENT_CANCEL_REQUESTED` | admin requested cancellation of a delayed payment |
| `COMPLETED` | order completed |
| `FAILED` | order failed or was cancelled |

## Error Codes

| HTTP Status | Code | Case |
|---:|---|---|
| 400 | `COMMON_MISSING_IDEMPOTENCY_KEY` | command request is missing `Idempotency-Key` |
| 400 | `ORDER_INVALID_REQUEST` | request body is invalid |
| 404 | `ORDER_NOT_FOUND` | target order does not exist |
| 500 | `ORDER_DATA_INTEGRITY_ERROR` | unexpected order data integrity failure |

## Client Polling Rule

Customer App polls `GET /api/orders/{orderId}` after `POST /api/orders` succeeds.

Stop polling when:

- `status = CONFIRMED` and `sagaStatus = COMPLETED`
- `status = CANCELLED` and `sagaStatus = FAILED`
- `sagaStatus = PAYMENT_DELAYED`
- repeated request failures exceed the client retry threshold
