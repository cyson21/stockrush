# Payment Service

Port: `18084`  
Schema: `payment`

Owns payment simulation state, payment events, outbox rows, and processed event records.

## Simulation Rules

| Requested method | Payment status | Outbox event |
|---|---|---|
| `CARD` | `AUTHORIZED` | `PaymentAuthorized` |
| `FAIL_CARD` | `FAILED` | `PaymentAuthorizationFailed` |

`FAIL_CARD` writes `failure_reason = PAYMENT_DECLINED` and keeps the original method in the payment row and event payload.
