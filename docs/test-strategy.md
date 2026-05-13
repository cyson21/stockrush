# Test Strategy

StockRush 테스트 전략은 한정 판매 주문 흐름에서 서비스별 도메인 로직, HTTP API, Kafka 이벤트, Outbox relay, 앱 화면까지 같은 시나리오로 검증하는 데 초점을 둔다.

## Goals

- 주문 생성 이후 `InventoryReserved`, `PaymentAuthorized`, `OrderConfirmed`로 이어지는 정상 Saga를 검증한다.
- `FAIL_CARD`와 `DELAY_CARD`처럼 의도적으로 실패하거나 지연되는 결제 흐름을 검증한다.
- Kafka 중복 수신, outbox retry, `Idempotency-Key`, `X-Correlation-Id`가 실제 코드와 문서 기준을 따르는지 확인한다.
- 프론트엔드가 백엔드 응답 모양과 상태 전이를 사용자 화면에 올바르게 반영하는지 확인한다.
- Architecture Guard로 기능 테스트가 놓치기 쉬운 구조 위반을 별도 차원에서 차단한다.

## Verification Layers

| Layer | Purpose | Representative files |
|---|---|---|
| Domain unit | 입력 검증, 계산, 기본 이벤트 생성 로직을 빠르게 확인 | `services/order-service/src/test/java/com/stockrush/order/application/CreateOrderServiceTest.java` |
| HTTP integration | Controller, validation, response shape, header propagation, DB persistence 확인 | `services/catalog-service/src/test/java/com/stockrush/catalog/api/CatalogProductControllerIntegrationTest.java`, `services/order-service/src/test/java/com/stockrush/order/api/CreateOrderControllerIntegrationTest.java`, `services/order-service/src/test/java/com/stockrush/order/api/AdminOrderCancelControllerIntegrationTest.java` |
| Saga handler integration | 소비 이벤트에 따른 주문 상태, 다음 command/event, 중복 처리 확인 | `services/order-service/src/test/java/com/stockrush/order/application/OrderSagaEventHandlerIntegrationTest.java`, `services/payment-service/src/test/java/com/stockrush/payment/application/PaymentAuthorizationHandlerIntegrationTest.java` |
| Outbox relay | `PENDING` claim, Kafka publish, retry, `PUBLISHED`/`FAILED` 전이를 확인 | `services/order-service/src/test/java/com/stockrush/order/infra/outbox/OutboxRelayServiceIntegrationTest.java`, `services/inventory-service/src/test/java/com/stockrush/inventory/infra/outbox/InventoryOutboxRelayServiceIntegrationTest.java`, `services/payment-service/src/test/java/com/stockrush/payment/infra/outbox/PaymentOutboxRelayServiceIntegrationTest.java` |
| Kafka smoke | 실제 로컬 Kafka에 publish/consume이 되는지 확인 | `services/inventory-service/src/test/java/com/stockrush/inventory/infra/kafka/InventoryKafkaSmokeIntegrationTest.java`, `services/payment-service/src/test/java/com/stockrush/payment/infra/kafka/PaymentKafkaSmokeIntegrationTest.java` |
| UI behavior | 고객/관리자 앱의 API 호출, 상태 렌더링, 재시도 키 재사용 확인 | `apps/customer-app/src/App.test.tsx`, `apps/admin-app/src/App.test.tsx` |
| Architecture Guard | schema ownership, Controller 반환 타입, event envelope, outbox table shape 확인 | `tools/architecture-guard/tests/test_architecture_guard.py`, `tools/architecture-guard/architecture_guard.py` |
| Manual E2E | 실제 서비스 기동 후 `CARD`, `FAIL_CARD`, `DELAY_CARD`, 관리자 취소, 동일 SKU 최종 상태 확인 | `docs/runbooks/local-e2e.md`, `tools/local-e2e` |

## Command Matrix

Backend tests run per service so a failing bounded context is immediately visible.

```bash
cd services/catalog-service && mvn test
cd services/inventory-service && mvn test
cd services/order-service && mvn test
cd services/payment-service && mvn test
```

Frontend verification runs both behavior tests and production build.

```bash
npm --prefix apps/customer-app test -- --run
npm --prefix apps/customer-app run build
npm --prefix apps/admin-app test -- --run
npm --prefix apps/admin-app run build
```

Architecture Guard is a separate quality gate.

```bash
./tools/architecture-guard/architecture-guard check
```

Local end-to-end verification follows [Local E2E Runbook](runbooks/local-e2e.md).

## Scenario Coverage

| Scenario | Automated proof | Manual proof |
|---|---|---|
| `CARD` order success | Order API, Inventory reservation, Payment authorization, Order Saga handler tests | `CARD 성공 시나리오` in `docs/runbooks/local-e2e.md` |
| `FAIL_CARD` cancellation and stock release | Payment failure, Order cancellation, Inventory release tests | `FAIL_CARD 실패 시나리오 + 재고 복구` |
| `DELAY_CARD` delayed payment | Payment delay, Order `PAYMENT_DELAYED`, Customer App delayed state tests | 로컬 서비스 E2E 증거: `ord_20260513012031_8c06cd49` |
| Admin delayed payment cancellation | Admin cancel API, `PaymentCancelRequested`, `PaymentCanceled`, Admin App retry key tests | 로컬 서비스 E2E 증거: `CANCELLED/FAILED`, 재고 복구 |
| Concurrent same-SKU reservation | Inventory handler PostgreSQL integration race test | `tools/local-e2e/local-e2e same-sku-concurrency`로 서비스 최종 상태 확인 |
| Outbox retry and relay | Service-local outbox relay tests | Admin App outbox operation checklist |
| Event duplicate handling | `processed_events` and replay tests in Order/Payment handlers | Outbox and Kafka UI checks |

최근 로컬 E2E 증거:

- `ord_20260513012031_8c06cd49` 주문이 `CREATED/PAYMENT_DELAYED`에 도달한 뒤 관리자 취소로 `CANCELLED/FAILED`가 됐고, SKU `DELAY-E2E-102029-S`는 `availableQuantity=20`, `reservedQuantity=0`으로 복구됐다. Order/Inventory/Payment `PENDING` outbox 목록은 모두 비어 있었다.
- `tools/local-e2e/local-e2e same-sku-concurrency --orders 6 --initial-stock 3 --quantity 1 --max-attempts 12` 실행에서 productCode `CONC-E2E-20260513104212-e3025d67`, SKU `CONC-E2E-20260513104212-e3025d67-S` 기준 주문 6건 중 3건은 `CONFIRMED/COMPLETED`, 3건은 `CANCELLED/FAILED`가 됐다. 최종 재고는 `availableQuantity=0`, `reservedQuantity=0`이고 Order/Inventory/Payment `pendingOutboxDelta`는 모두 0이었다.

## Stability Rules

- Command HTTP requests must carry `Idempotency-Key`.
- HTTP and Kafka flows should preserve `X-Correlation-Id` or `correlationId`.
- Consumers insert `processed_events` in the same transaction as state changes.
- Outbox rows are written with domain state before Kafka publish is attempted.
- Replay of already processed cancel commands must be harmless.
- Tests should assert both business state and operational state where possible: order status, saga status, outbox event type, retry status, and stock quantities.

## Current Gaps

These are known gaps, not hidden assumptions.

- Gateway has no routing test yet; current E2E calls service ports directly.
- Inventory handler has a focused same-SKU concurrent reservation regression test and a local final-state E2E runner. Kafka consumer parallelism, external load benchmarking, and duplicate command race windows remain future scope.
- Kafka broker outage and long-lived `PENDING`/`FAILED` recovery scenarios are documented but not fully automated.
- Authentication and authorization tests are outside the current public slice.
- Customer API documentation is now separated from runbook examples, but inventory customer query docs can still be expanded later.

## Related Docs

- [Local E2E Runbook](runbooks/local-e2e.md)
- [Phase 1 Commerce Foundation](architecture/phase-1-commerce-foundation.md)
- [Event Envelope](architecture/events.md)
- [Outbox and Consumer Idempotency](architecture/outbox.md)
- [Architecture Guard Rules](architecture/architecture-guard-rules.md)
- [Common API Response](api/common.md)
