# Admin Order API

Admin Order API는 관리자 앱에서 주문 진행 상태와 Saga 실패 원인을 확인하기 위한 order-service 조회 API이다.

## List Recent Orders

`GET /api/admin/orders`

### Query Parameters

| Name | Required | Default | Description |
|---|---:|---|---|
| `page` | no | `0` | zero-based page |
| `size` | no | `20` | page size, 1 to 100 |
| `status` | no | none | `CREATED`, `CONFIRMED`, `CANCELLED` |
| `sagaStatus` | no | none | `STARTED`, `INVENTORY_REQUESTED`, `PAYMENT_REQUESTED`, `PAYMENT_DELAYED`, `COMPLETED`, `FAILED` |

### Response Data

```json
{
  "page": 0,
  "size": 20,
  "items": [
    {
      "orderId": "ord_20260513_001",
      "memberId": "member-1",
      "status": "CANCELLED",
      "sagaStatus": "FAILED",
      "paymentMethod": "FAIL_CARD",
      "totalAmount": 15000.0,
      "itemCount": 1,
      "createdAt": "2026-05-13T03:00:00Z",
      "updatedAt": "2026-05-13T03:05:00Z"
    }
  ]
}
```

## Get Saga Status

`GET /api/admin/orders/{orderId}/saga`

### Response Data

```json
{
  "orderId": "ord_20260513_001",
  "orderStatus": "CANCELLED",
  "sagaStatus": "FAILED",
  "failedAt": "2026-05-13T03:05:00Z",
  "businessReason": "PAYMENT_DECLINED",
  "technicalErrorMessage": null,
  "lastEventType": "OrderCancelled",
  "outboxAttempts": 0
}
```

## Notes

- `businessReason` is derived from the latest order outbox payload when available.
- `technicalErrorMessage` is derived from the latest order outbox row publish error when available.
- This API reads only the order-service schema. Inventory and Payment operational events remain owned by their services.
