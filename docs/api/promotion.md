# Promotion API

Promotion API는 쿠폰 정의와 주문 전 할인 견적을 다룬다. 주문 이벤트 소비로 쿠폰 사용 상태도 기록한다.

Base URLs:

- Gateway customer quote: `http://localhost:18080`
- Service-local admin and direct service check: `http://localhost:18085`

## Admin: Create Coupon

`POST /api/admin/coupons`

Headers:

| Header | Required | Description |
|---|---:|---|
| `Idempotency-Key` | yes | same key + same body returns the existing coupon; same key + different body returns `409` |
| `X-Correlation-Id` | no | returned in response trace |

Request:

```json
{
  "couponCode": "SPRING15",
  "name": "Spring 15%",
  "discountType": "PERCENTAGE",
  "discountValue": 15.00,
  "minOrderAmount": 30000.00,
  "maxDiscountAmount": 7000.00,
  "status": "ACTIVE",
  "startsAt": "2026-05-01T00:00:00Z",
  "endsAt": "2026-12-31T23:59:59Z"
}
```

Response: `201 Created`

```json
{
  "success": true,
  "data": {
    "couponCode": "SPRING15",
    "name": "Spring 15%",
    "discountType": "PERCENTAGE",
    "discountValue": 15.00,
    "minOrderAmount": 30000.00,
    "maxDiscountAmount": 7000.00,
    "status": "ACTIVE",
    "startsAt": "2026-05-01T00:00:00Z",
    "endsAt": "2026-12-31T23:59:59Z"
  },
  "error": null,
  "trace": {
    "correlationId": "corr-promotion-create"
  }
}
```

Replaying the same `Idempotency-Key` with the same request body returns `200 OK` with the same coupon data. Reusing the key with a different request body returns `409 PROMOTION_IDEMPOTENCY_KEY_CONFLICT`.

## Admin: List Coupons

`GET /api/admin/coupons?status=ACTIVE`

Returns coupons filtered by `status` in creation order.

## Admin: Get Coupon

`GET /api/admin/coupons/{couponCode}`

Returns the coupon detail exposed by the `Location` header from create.

## Admin: List Coupon Usages

`GET /api/admin/coupon-usages?couponCode=WELCOME10&memberId=member-a&status=CONSUMED&page=0&size=20`

This endpoint is available through Gateway and Promotion Service with the same path. Query parameters are optional. `page` is normalized to `0` or greater, and `size` is capped at `100`.

Response:

```json
{
  "success": true,
  "data": {
    "page": 0,
    "size": 20,
    "items": [
      {
        "orderId": "ord_coupon_usage_001",
        "memberId": "member-a",
        "couponCode": "WELCOME10",
        "status": "CONSUMED",
        "orderAmount": 80000.00,
        "discountAmount": 5000.00,
        "payableAmount": 75000.00,
        "reservedAt": "2026-05-13T04:30:00Z",
        "consumedAt": "2026-05-13T04:31:00Z",
        "releasedAt": null,
        "releaseReason": null,
        "updatedAt": "2026-05-13T04:31:00Z"
      }
    ]
  },
  "error": null,
  "trace": {
    "correlationId": "corr-promotion-usage-list"
  }
}
```

## Customer: Quote Coupon

`POST /api/coupons/quote`

Customer quote is available through Gateway and Promotion Service with the same path. Mobile and external demo clients should use the Gateway route.

Request:

```json
{
  "couponCode": "WELCOME10",
  "orderAmount": 80000.00
}
```

Response:

```json
{
  "success": true,
  "data": {
    "couponCode": "WELCOME10",
    "applied": true,
    "discountAmount": 5000.00,
    "payAmount": 75000.00,
    "reason": "APPLIED"
  },
  "error": null,
  "trace": {
    "correlationId": "corr-promotion-quote"
  }
}
```

## Discount Rules

| Type | Rule |
|---|---|
| `PERCENTAGE` | `orderAmount * discountValue / 100`, rounded down to 2 decimal places |
| `FIXED_AMOUNT` | fixed discount amount |
| `maxDiscountAmount` | caps percentage discount when present |
| `minOrderAmount` | returns `applied=false` when not met |
| period | returns `applied=false` with `COUPON_OUT_OF_PERIOD` when outside `startsAt` and `endsAt` |
| status | returns `applied=false` with `COUPON_NOT_ACTIVE` when not `ACTIVE` |

## Order Event Consumer

Promotion Service consumes `stockrush.order.events.v1` when `PROMOTION_KAFKA_LISTENERS_ENABLED=true`.

| Event | Required payload fields | Usage state |
|---|---|---|
| `OrderCreated` | `orderId`, `memberId`, `couponCode`, `totalAmount`, `discountAmount`, `payableAmount`, `createdAt` | insert `RESERVED` usage when `couponCode` is present |
| `OrderConfirmed` | `orderId`, `confirmedAt` | move matching `RESERVED` usage to `CONSUMED` |
| `OrderCancelled` | `orderId`, `reason`, `cancelledAt` | move matching `RESERVED` usage to `RELEASED` |

Duplicate event delivery is handled by the Promotion `processed_events` table with `(event_id, consumer_group)` as the unique key.

## Error Codes

| Status | Code | Case |
|---:|---|---|
| 400 | `PROMOTION_INVALID_REQUEST` | invalid body, status, type, or amount |
| 400 | `COMMON_MISSING_IDEMPOTENCY_KEY` | admin command missing `Idempotency-Key` |
| 404 | `PROMOTION_COUPON_NOT_FOUND` | quote request for unknown coupon |
| 409 | `PROMOTION_DUPLICATE_COUPON_CODE` | duplicate coupon code |
| 409 | `PROMOTION_IDEMPOTENCY_KEY_CONFLICT` | reused idempotency key with a different request body |
| 500 | `PROMOTION_DATA_INTEGRITY_ERROR` | unexpected promotion data integrity failure |
