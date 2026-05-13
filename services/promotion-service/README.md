# Promotion Service

Port: `18085`
Schema: `promotion`

Promotion Service owns coupon definitions and discount quote calculation.

## APIs

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/admin/coupons` | create coupon |
| `GET` | `/api/admin/coupons?status=ACTIVE` | list coupons by status |
| `GET` | `/api/admin/coupons/{couponCode}` | get coupon detail |
| `POST` | `/api/coupons/quote` | calculate coupon discount quote |
| `GET` | `/ping` | local ping |

This first slice is service-local. Gateway routing, app UI, and Order Saga integration remain future scope.

Admin create requests require `Idempotency-Key`. Reusing the same key with the same request body returns the existing coupon; reusing it with a different body returns `409`.

## Run

```bash
mvn spring-boot:run
```

## Verify

```bash
mvn test
```
