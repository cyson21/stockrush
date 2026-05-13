# Inventory Service

Port: `18082`  
Schema: `inventory`

Owns stock items, reservations, inventory events, outbox rows, and processed event records.

## Event Handling

| Consumed event | Action | Published event |
|---|---|---|
| `OrderCreated` | Decrease available stock and create `RESERVED` reservations. | `InventoryReserved` or `InventoryReservationFailed` |
| `OrderConfirmed` | Change `RESERVED` reservations to `CONFIRMED` and decrease `reservedQuantity`. | `InventoryReservationConfirmed` |
| `OrderCancelled` | Change `RESERVED` reservations to `RELEASED`, restore `availableQuantity`, and decrease `reservedQuantity`. | `InventoryReservationReleased` |

`OrderCreated` 재고 선점은 SKU별 요청 수량을 먼저 합산하고 PostgreSQL row lock과 조건부 차감을 적용한다. 동일 SKU 경합 요청에서도 `availableQuantity` 초과 선점이 발생하지 않으며, 부족하면 `InventoryReservationFailed`를 기록한다.

## HTTP API

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/stocks?productCode={productCode}` | 상품 코드 기준 재고 목록을 조회한다. |
| `GET` | `/api/stocks/{skuId}` | SKU 기준 재고 상세를 조회한다. |
| `PUT` | `/api/stocks/{skuId}` | SKU 재고 수량을 설정한다. 없는 SKU는 새로 만든다. |

### `PUT /api/stocks/{skuId}` request

```json
{
  "productCode": "LIMITED-001",
  "availableQuantity": 12
}
```

### Response shape

```json
{
  "success": true,
  "data": {
    "skuId": "SKU-001",
    "productCode": "LIMITED-001",
    "availableQuantity": 12,
    "reservedQuantity": 2,
    "version": 3
  },
  "error": null,
  "trace": {
    "correlationId": "correlation-id"
  }
}
```
