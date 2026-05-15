# Promotion Service

Port: `18085`
Schema: `promotion`

Promotion Service owns coupon definitions, discount quote calculation, and order-event-driven coupon usage state.

## APIs

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/admin/coupons` | create coupon |
| `GET` | `/api/admin/coupons?status=ACTIVE` | list coupons by status |
| `GET` | `/api/admin/coupons/{couponCode}` | get coupon detail |
| `GET` | `/api/admin/coupon-usages` | list coupon usage history |
| `POST` | `/api/coupons/quote` | calculate coupon discount quote |
| `GET` | `/ping` | local ping |

Gateway routes coupon quote and admin coupon usage history. Customer App and Order Service already use the quote API, and the service can consume order events for coupon usage state.

Admin create requests require `Idempotency-Key`. Reusing the same key with the same request body returns the existing coupon; reusing it with a different body returns `409`.

## Event Handling

When `PROMOTION_KAFKA_LISTENERS_ENABLED=true`, the service consumes `stockrush.order.events.v1`.

| Event | Action |
|---|---|
| `OrderCreated` with coupon fields | insert `coupon_usages` row as `RESERVED` |
| `OrderConfirmed` | move usage to `CONSUMED` |
| `OrderCancelled` | move usage to `RELEASED` |

Processed event ids are stored in `processed_events` so duplicate delivery is harmless.

## Run

```bash
../../scripts/with-java17.sh mvn spring-boot:run
```

## Verify

```bash
../../scripts/with-java17.sh mvn test
```
