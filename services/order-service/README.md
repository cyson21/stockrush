# Order Service

Port: `18083`  
Schema: `orders`

Owns orders, order items, order events, outbox rows, processed event records, and the Saga Orchestrator.

## HTTP API

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/orders` | 주문을 생성하고 `OrderCreated` outbox event를 기록한다. |
| `GET` | `/api/orders/{orderId}` | 주문 상태, Saga 상태, 결제수단, 주문 항목을 조회한다. |

### `POST /api/orders` request

`paymentMethod` is optional. If omitted, Order Service stores `CARD`.

```json
{
  "memberId": "member-1",
  "paymentMethod": "CARD",
  "items": [
    {
      "productCode": "LIMITED-001",
      "skuId": "SKU-001",
      "quantity": 2,
      "unitPrice": 12000.00
    }
  ]
}
```

`FAIL_CARD` can be used in local scenarios to trigger `PaymentAuthorizationFailed` from Payment Service after inventory reservation succeeds.

### `GET /api/orders/{orderId}` response

```json
{
  "success": true,
  "data": {
    "orderId": "ord_query_001",
    "memberId": "member-query-1",
    "status": "CONFIRMED",
    "sagaStatus": "COMPLETED",
    "paymentMethod": "CARD",
    "totalAmount": 29000.00,
    "items": [
      {
        "productCode": "LIMITED-001",
        "skuId": "SKU-001",
        "quantity": 2,
        "unitPrice": 12000.00,
        "lineAmount": 24000.00
      }
    ]
  },
  "trace": {
    "correlationId": "corr-order-query"
  }
}
```

Unknown orders return `404` with `ORDER_NOT_FOUND`.
