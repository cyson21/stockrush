# Gateway

Port: `18080`  
Schema: none

Phase 1 keeps the gateway light and focused on routing smoke coverage.

## Routes

| Method | Path | Upstream |
|---|---|---|
| `POST` | `/api/orders` | Order Service `POST /api/orders` |
| `GET` | `/api/orders/{orderId}` | Order Service `GET /api/orders/{orderId}` |
| `GET` | `/api/admin/orders` | Order Service `GET /api/admin/orders` |
| `GET` | `/api/admin/orders/{orderId}/saga` | Order Service `GET /api/admin/orders/{orderId}/saga` |
| `POST` | `/api/admin/orders/{orderId}/cancel` | Order Service `POST /api/admin/orders/{orderId}/cancel` |
| `GET` | `/internal/ping` | Gateway local health-style ping |

## Configuration

| Environment variable | Default | Description |
|---|---|---|
| `GATEWAY_PORT` | `18080` | Gateway HTTP port |
| `ORDER_SERVICE_URL` | `http://localhost:18083` | Order Service base URL |

## Verification

```bash
JAVA_HOME=/Users/chanyang.son/Library/Java/JavaVirtualMachines/ms-17.0.18/Contents/Home mvn test
```

The routing smoke test uses a fake upstream Order Service and verifies method, path, query string, body, `Idempotency-Key`, `X-Correlation-Id`, response status, `Location`, and response body propagation for customer order routes and admin order routes.

Outbox admin routing, auth, and broader service routing remain future scope.
