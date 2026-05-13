# Payment Service

Port: `18084`  
Schema: `payment`

Owns payment simulation state, payment events, outbox rows, and processed event records.

## Simulation Rules

| Requested method | Payment status | Outbox event |
|---|---|---|
| `CARD` | `AUTHORIZED` | `PaymentAuthorized` |
| `FAIL_CARD` | `FAILED` | `PaymentAuthorizationFailed` |
| `DELAY_CARD` | `DELAYED` | `PaymentAuthorizationDelayed` |
| `PaymentCancelRequested` command | `CANCELED` | `PaymentCanceled` |

`FAIL_CARD` writes `failure_reason = PAYMENT_DECLINED` and keeps the original method in the payment row and event payload.
`DELAY_CARD` writes `failure_reason = PAYMENT_DELAYED` so Order Service can keep the order open with Saga status `PAYMENT_DELAYED`.
`PaymentCancelRequested` is accepted for delayed payments and writes `failure_reason = PAYMENT_CANCELED`; already canceled payments are treated as a harmless replay.
