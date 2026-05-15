# Gateway

Port: `18080`  
Schema: none

Phase 1 keeps the gateway light and focused on routing smoke coverage.

## Routes

| Method | Path | Access | Upstream |
|---|---|---|---|
| `GET` | `/api/products` | public via Gateway | Catalog Service `GET /api/products` |
| `GET` | `/api/stocks` | public via Gateway | Inventory Service `GET /api/stocks` |
| `POST` | `/api/coupons/quote` | public via Gateway | Promotion Service `POST /api/coupons/quote` |
| `POST` | `/api/orders` | `ROLE_CUSTOMER` + subject forwarding | Order Service `POST /api/orders` |
| `GET` | `/api/orders/{orderId}` | `ROLE_CUSTOMER` + subject/ownership forwarding | Order Service `GET /api/orders/{orderId}` |
| `GET` | `/api/read-model/orders` | `ROLE_CUSTOMER` + subject forwarding | Read Model Service `GET /api/read-model/orders` |
| `GET` | `/api/admin/orders` | `ROLE_ADMIN` + gateway operator principal | Order Service `GET /api/admin/orders` |
| `GET` | `/api/admin/orders/{orderId}/saga` | `ROLE_ADMIN` + gateway operator principal | Order Service `GET /api/admin/orders/{orderId}/saga` |
| `POST` | `/api/admin/orders/{orderId}/cancel` | `ROLE_ADMIN` + gateway operator principal | Order Service `POST /api/admin/orders/{orderId}/cancel` |
| `GET` | `/api/admin/outbox-services/{service}/events` | `ROLE_ADMIN` + gateway operator principal | Selected service `GET /api/admin/outbox-events` |
| `POST` | `/api/admin/outbox-services/{service}/events/retry` | `ROLE_ADMIN` + gateway operator principal | Selected service `POST /api/admin/outbox-events/retry` |
| `POST` | `/api/admin/outbox-services/{service}/events/failed/requeue` | `ROLE_ADMIN` + gateway operator principal | Selected service `POST /api/admin/outbox-events/failed/requeue` |
| `GET` | `/api/admin/coupon-usages` | `ROLE_ADMIN` + gateway operator principal | Promotion Service `GET /api/admin/coupon-usages` |
| `GET` | `/api/admin/fulfillment-requests` | `ROLE_ADMIN` + gateway operator principal | Fulfillment Service `GET /api/admin/fulfillment-requests` |
| `GET` | `/api/read-model/admin/orders` | `ROLE_ADMIN` + gateway operator principal | Read Model Service `GET /api/read-model/admin/orders` |
| `GET` | `/internal/ping` | internal/dev helper | Gateway local health-style ping |

Outbox `service` is restricted to `order`, `inventory`, or `payment`.

## Route policy notes

- Customer web/mobile 공개 API는 Gateway 경유 `GET /api/products`, `GET /api/stocks`, `POST /api/coupons/quote`만 허용한다.
- 주문 생성/주문 상세/주문 내역은 `ROLE_CUSTOMER` 보호와 subject 기반 내부 헤더 전달이 필요하다.
- admin 영역은 `ROLE_ADMIN` 보호와 `X-Operator-Id` 재생성 후 전달을 통해 감사 주체를 보장한다.
- service-local 경로(직접 서비스 포트 호출)는 내부/dev 환경에서만 사용하고, 공개 포트폴리오 진입점으로 노출하지 않는다.

## Configuration

| Environment variable | Default | Description |
|---|---|---|
| `GATEWAY_PORT` | `18080` | Gateway HTTP port |
| `ORDER_SERVICE_URL` | `http://localhost:18083` | Order Service base URL |
| `INVENTORY_SERVICE_URL` | `http://localhost:18082` | Inventory Service base URL |
| `PAYMENT_SERVICE_URL` | `http://localhost:18084` | Payment Service base URL |
| `PROMOTION_SERVICE_URL` | `http://localhost:18085` | Promotion Service base URL |
| `FULFILLMENT_SERVICE_URL` | `http://localhost:18086` | Fulfillment Service base URL |
| `READ_MODEL_SERVICE_URL` | `http://localhost:18087` | Read Model Service base URL |

## Verification

```bash
../../scripts/with-java17.sh mvn test
```

The routing smoke test uses fake upstream services and verifies method, path, query string, body, `Idempotency-Key`, `X-Correlation-Id`, `X-Operator-Id`, response status, `Location`, and response body propagation for customer order routes, admin order routes, Outbox admin routes including failed requeue, coupon quote, coupon usage history, fulfillment request history, and Read Model order summary routes.

Auth and broader service routing remain future scope.
