# Local E2E Runbook (StockRush)

이 문서는 채용 담당자, 면접관, 개발자가 로컬에서 무엇을 검증했는지 빠르게 확인할 수 있도록 만들었습니다.

## Scope

- Customer App, Admin App, Catalog / Inventory / Order / Payment / Promotion 서비스의 연동 확인
- Promotion Service 쿠폰 API와 Customer App 쿠폰 견적 UI, Order Service 주문 할인 반영 확인
- Kafka 이벤트 발행/소비와 서비스 로컬 Outbox 동작 확인
- `CARD`(성공), `FAIL_CARD`(실패·재고 복구), `DELAY_CARD`(결제 지연) 시나리오 재현
- 운영 화면(관리자) 점검 포인트와 Outbox retry/requeue 경로 정리

## Prerequisites

- Docker Desktop
- Windows 11에서 실행할 때는 WSL2 + Docker Desktop
- Java 17
- Maven 3.9+
- Node.js 20 + npm
- `jq`
- `mvn -version`, `node -v`, `npm -v`, `jq --version`이 동작해야 함
- 포트 충돌이 없어야 함:
  - 15432(PostgreSQL), 16379(Redis), 19092(Kafka), 19090(Kafka UI)
  - 18080~18087(Backend), 5173, 5174(Frontend)

## Runtime Modes

이 runbook은 개발 모드 기준이다.

- 개발 모드: `infra/local` Docker Compose로 PostgreSQL/Redis/Kafka만 실행하고, Spring Boot 서비스와 앱은 host에서 실행한다.
- 데모 모드: `infra/demo` Docker Compose로 인프라, 백엔드, 웹앱을 함께 실행한다. 데모 모드는 개발 모드와 동시에 띄우기 쉽도록 별도 host port 대역(`28080~28087`, `15173~15174`, `25432`, `26379`, `29092`, `29090`)을 쓴다. macOS/Windows 11 실행법은 `infra/demo/README.md`에 둔다.

## Infrastructure

```bash
cd infra/local
cp .env.example .env
docker compose up -d
docker compose ps
```

## Service Ports

| Layer | Port | URL |
|---|---:|---|
| Gateway | 18080 | `http://localhost:18080` |
| Catalog Service | 18081 | `http://localhost:18081` |
| Inventory Service | 18082 | `http://localhost:18082` |
| Order Service | 18083 | `http://localhost:18083` |
| Payment Service | 18084 | `http://localhost:18084` |
| Promotion Service | 18085 | `http://localhost:18085` |
| Fulfillment Service | 18086 | `http://localhost:18086` |
| Read Model Service | 18087 | `http://localhost:18087` |
| Customer App | 5173 | `http://localhost:5173` |
| Admin App | 5174 | `http://localhost:5174` |
| PostgreSQL | 15432 | `localhost:15432` |
| Redis | 16379 | `localhost:16379` |
| Kafka | 19092 | `localhost:19092` |
| Kafka UI | 19090 | `http://localhost:19090` |

## Startup Steps

1. Start backend services.

아래 명령은 repo root 기준이며, 각 줄을 별도 터미널에서 실행한다.

```bash
cd services/gateway && mvn spring-boot:run
cd services/catalog-service && mvn spring-boot:run
cd services/inventory-service && STOCKRUSH_KAFKA_LISTENERS_ENABLED=true mvn spring-boot:run
cd services/order-service && STOCKRUSH_KAFKA_LISTENERS_ENABLED=true mvn spring-boot:run
cd services/payment-service && STOCKRUSH_KAFKA_LISTENERS_ENABLED=true mvn spring-boot:run
cd services/promotion-service && PROMOTION_KAFKA_LISTENERS_ENABLED=true mvn spring-boot:run
cd services/fulfillment-service && FULFILLMENT_KAFKA_LISTENERS_ENABLED=true mvn spring-boot:run
cd services/read-model-service && READ_MODEL_KAFKA_LISTENERS_ENABLED=true mvn spring-boot:run
```

2. Start frontend apps.

아래 명령도 각 앱별 별도 터미널에서 실행한다.

```bash
cd apps/customer-app && npm install && npm run dev
cd apps/admin-app && npm install && npm run dev
```

3. Verify health:

```bash
curl -sSf http://localhost:18080/actuator/health
curl -sSf http://localhost:18081/actuator/health
curl -sSf http://localhost:18082/actuator/health
curl -sSf http://localhost:18083/actuator/health
curl -sSf http://localhost:18084/actuator/health
curl -sSf http://localhost:18085/actuator/health
curl -sSf http://localhost:18086/actuator/health
curl -sSf http://localhost:18087/actuator/health
```

4. (옵션) 브라우저로 확인:

- Customer App: `http://localhost:5173`
- Admin App: `http://localhost:5174`
- Kafka UI: `http://localhost:19090`

## Gateway 주문/운영 라우팅 Smoke

Gateway는 주문 생성/조회, 관리자 주문 조회/취소, Outbox 조회/재시도/requeue, 쿠폰 견적, Read Model 주문 요약 라우팅 smoke를 제공한다. 이 테스트는 Gateway가 대상 서비스로 method, path, query string, body, 핵심 헤더와 응답을 전달하는지를 fake upstream으로 고정한다.

```bash
cd services/gateway
JAVA_HOME=/Users/chanyang.son/Library/Java/JavaVirtualMachines/ms-17.0.18/Contents/Home mvn test
```

로컬 기동 시 Gateway는 기본적으로 `ORDER_SERVICE_URL=http://localhost:18083`, `INVENTORY_SERVICE_URL=http://localhost:18082`, `PAYMENT_SERVICE_URL=http://localhost:18084`, `PROMOTION_SERVICE_URL=http://localhost:18085`, `READ_MODEL_SERVICE_URL=http://localhost:18087`로 각 서비스를 호출한다. Outbox admin API는 `service` path 값으로 `order`, `inventory`, `payment`를 선택한다.

Promotion Service는 Gateway의 `/api/coupons/quote` route 또는 service-local API로 확인한다. Customer App은 아직 `/promotion` Vite proxy로 quote API를 호출하고, Order Service는 `PROMOTION_SERVICE_URL`로 quote API를 호출한다. `PROMOTION_KAFKA_LISTENERS_ENABLED=true`로 기동하면 주문 이벤트를 소비해 쿠폰 사용 상태를 `RESERVED`, `CONSUMED`, `RELEASED`로 기록한다.

Fulfillment Service는 현재 이벤트 기반 출고 준비 요청 기록을 담당한다. Read Model Service는 service-local 조회 API와 Gateway의 `/api/read-model/orders`, `/api/read-model/admin/orders` route로 주문 요약 projection을 제공한다.

## 반복 실행용 Local E2E Runner

수동 curl 절차 외에 동일 SKU 최종 상태와 대량 멱등 재시도 수렴을 반복 확인하는 CLI를 제공한다. 기본값은 주문 생성/조회와 outbox 조회/재시도를 Gateway(`http://localhost:18080`)로 보낸다. 로컬 회귀 확인용이며 외부 부하 벤치마크나 Kafka consumer 병렬성 검증으로 해석하지 않는다.

### 동일 SKU Concurrency

같은 SKU로 주문 생성 API를 병렬 호출한 뒤, Gateway Outbox route로 서비스별 retry API를 순차 호출해 최종 주문/재고/outbox 상태를 확인한다.

```bash
./tools/local-e2e/local-e2e same-sku-concurrency \
  --orders 6 \
  --initial-stock 3 \
  --quantity 1 \
  --max-attempts 12
```

기대 결과:

- `CONFIRMED/COMPLETED` 3건
- `CANCELLED/FAILED` 3건
- 최종 재고 `availableQuantity=0`, `reservedQuantity=0`
- Order/Inventory/Payment `pendingOutboxDelta=0`

### Burst Idempotency

```bash
./tools/local-e2e/local-e2e burst-idempotency \
  --orders 30 \
  --initial-stock 10 \
  --idempotency-replays 2 \
  --relay-workers 4 \
  --stability-waves 2
```

검증 기준:

- productCode는 실행마다 생성되는 값을 결과 증거로 기록한다.
- requestAttemptCount `60`
- unique orderIds `30`
- `CONFIRMED/COMPLETED` 10건, `CANCELLED/FAILED` 20건, `unresolved` 0건
- 재고 `availableQuantity=0`, `reservedQuantity=0`
- Order/Inventory/Payment `pendingOutboxDelta` `0`
- postReplayPendingOutboxDelta(Order/Inventory/Payment) `0`
- `--allow-existing-pending` 사용 시에도 이번 실행 이후 새로 남은 pending outbox event ID가 없어야 한다.

최근 burst-idempotency 검증 증거:

- 실행 시각: 2026-05-14 17:56
- productCode: `BURST-E2E-20260514175639-9f933e0f`
- requestAttemptCount: `60`, unique orderIds: `30`
- 주문 상태: `CONFIRMED/COMPLETED` 10건, `CANCELLED/FAILED` 20건, `unresolved` 0건
- 재고: `availableQuantity=0`, `reservedQuantity=0`
- Order/Inventory/Payment `pendingOutboxDelta`, `postReplayPendingOutboxDelta`: 모두 `0`
- `pendingOutboxNewEventIds`, `postReplayPendingOutboxNewEventIds`: 모두 비어 있음

데모 스택 smoke에는 성공/실패/지연결제 취소 흐름을 한 번에 확인하는 명령을 사용한다.

```bash
./tools/local-e2e/local-e2e demo-order-flow \
  --initial-stock 20 \
  --quantity 1 \
  --max-attempts 12 \
  --promotion-url http://localhost:18085
```

기대 결과:

- 고유 쿠폰이 Promotion Service 관리자 API로 생성된다.
- 쿠폰 견적은 Gateway의 `/api/coupons/quote` 경로로 확인된다.
- `CARD`: `CONFIRMED/COMPLETED`
- `CARD` 주문의 `couponCode`, `discountAmount`, `payableAmount`가 쿠폰 견적과 일치
- `FAIL_CARD`: `CANCELLED/FAILED`
- `DELAY_CARD`: `PAYMENT_DELAYED` 확인 후 관리자 취소로 `CANCELLED/FAILED`
- 최종 재고 `availableQuantity=19`, `reservedQuantity=0`
- Order/Inventory/Payment `pendingOutboxDelta=0`

최근 Gateway 경유 검증 증거:

- 실행 시각: 2026-05-13 11:02 KST
- productCode: `CONC-E2E-20260513110249-07e052f0`
- skuId: `CONC-E2E-20260513110249-07e052f0-S`
- 주문 6건 중 3건 `CONFIRMED/COMPLETED`, 3건 `CANCELLED/FAILED`
- 최종 재고: `availableQuantity=0`, `reservedQuantity=0`
- `pendingOutboxBaseline`, `pendingOutboxCounts`, `pendingOutboxDelta`: Order/Inventory/Payment 모두 `0`

최근 Gateway 주문 시나리오 검증 증거:

- 실행 시각: 2026-05-13 11:19 KST
- productCode: `GW-E2E-20260513111940-332ba0dc`
- skuId: `GW-E2E-20260513111940-332ba0dc-S`
- Gateway(`http://localhost:18080`)로 `CARD`, `FAIL_CARD`, `DELAY_CARD` 주문 생성/조회와 지연 결제 취소 요청을 검증
- 주문 결과:
  - `CARD`: `ord_20260513021940_57874d04` -> `CONFIRMED/COMPLETED`
  - `FAIL_CARD`: `ord_20260513021940_5ea74236` -> `CANCELLED/FAILED`
  - `DELAY_CARD`: `ord_20260513021940_8c5b95cf` -> `PAYMENT_DELAYED` 확인 후 Gateway 관리자 취소로 `CANCELLED/FAILED`
- 최종 재고: `availableQuantity=19`, `reservedQuantity=0`
- `pendingOutboxBaseline`, `pendingOutboxCounts`, `pendingOutboxDelta`: Order/Inventory/Payment 모두 `0`

## CARD 성공 시나리오

1. 운영용 샘플 상품/재고를 준비한다.

```bash
PRODUCT_CODE=DEMO-001
SKU_ID=DEMO-001-S
MEMBER_ID=member-demo

curl -sS -X POST http://localhost:18081/api/admin/products \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: seed-product-card' \
  -d "{\"productCode\":\"$PRODUCT_CODE\",\"name\":\"Demo Product\",\"salesStatus\":\"ON_SALE\",\"listPrice\":12000}"

curl -sS -X PUT http://localhost:18082/api/stocks/"$SKU_ID" \
  -H 'Content-Type: application/json' \
  -d "{\"productCode\":\"$PRODUCT_CODE\",\"availableQuantity\":20}"
```

2. 주문 생성 API 호출:

```bash
CARD_ORDER_ID=$(curl -sS -X POST http://localhost:18080/api/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: card-demo-001' \
  -d "{\"memberId\":\"$MEMBER_ID\",\"paymentMethod\":\"CARD\",\"items\":[{\"productCode\":\"$PRODUCT_CODE\",\"skuId\":\"$SKU_ID\",\"quantity\":1,\"unitPrice\":12000}]}" \
  | jq -r '.data.orderId')

echo "$CARD_ORDER_ID"
```

3. 상태 폴링:

```bash
curl -sS http://localhost:18080/api/orders/"$CARD_ORDER_ID"
```

예상 결과:

- 주문 상태: `CONFIRMED`
- Saga 상태: `COMPLETED`
- 재고: 주문 수량만큼 예약/감소 후 `CONFIRMED` 단계에서 정산됨

4. 이벤트 흐름 확인:

```bash
curl -sS 'http://localhost:18080/api/admin/outbox-services/order/events?status=PUBLISHED'
curl -sS 'http://localhost:18080/api/admin/outbox-services/inventory/events?status=PUBLISHED'
curl -sS 'http://localhost:18080/api/admin/outbox-services/payment/events?status=PUBLISHED'
```

5. 장기 체류 이벤트 확인:

```bash
curl -sS 'http://localhost:18080/api/admin/outbox-services/order/events?status=PENDING,FAILED'
curl -sS 'http://localhost:18080/api/admin/outbox-services/inventory/events?status=PENDING,FAILED'
curl -sS 'http://localhost:18080/api/admin/outbox-services/payment/events?status=PENDING,FAILED'
```

## FAIL_CARD 실패 시나리오 + 재고 복구

동일한 샘플 상품을 사용해 결제 실패를 강제한다.

```bash
FAIL_ORDER_ID=$(curl -sS -X POST http://localhost:18080/api/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: failcard-demo-001' \
  -d "{\"memberId\":\"$MEMBER_ID\",\"paymentMethod\":\"FAIL_CARD\",\"items\":[{\"productCode\":\"$PRODUCT_CODE\",\"skuId\":\"$SKU_ID\",\"quantity\":1,\"unitPrice\":12000}]}" \
  | jq -r '.data.orderId')

echo "$FAIL_ORDER_ID"
```

1. 상태 조회:

```bash
curl -sS http://localhost:18080/api/orders/"$FAIL_ORDER_ID"
```

2. 재고 복구 확인:

```bash
curl -sS http://localhost:18082/api/stocks/"$SKU_ID"
```

예상 결과:

- 주문 상태: `CANCELLED`
- Saga 상태: `FAILED`
- `FAIL_CARD` 실패 후 `availableQuantity`는 복구되고 `reservedQuantity`는 증가하지 않음
- 주문 상세에서 실패 사유(`businessReason`)에 `PAYMENT_DECLINED`가 보임

## DELAY_CARD 지연 시나리오

결제 지연을 강제해 주문이 열린 상태로 남는지 확인한다.

```bash
DELAY_ORDER_ID=$(curl -sS -X POST http://localhost:18080/api/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: delaycard-demo-001' \
  -d "{\"memberId\":\"$MEMBER_ID\",\"paymentMethod\":\"DELAY_CARD\",\"items\":[{\"productCode\":\"$PRODUCT_CODE\",\"skuId\":\"$SKU_ID\",\"quantity\":1,\"unitPrice\":12000}]}" \
  | jq -r '.data.orderId')

echo "$DELAY_ORDER_ID"
```

상태 조회:

```bash
curl -sS http://localhost:18080/api/orders/"$DELAY_ORDER_ID"
```

예상 결과:

- 결제 outbox에 `PaymentAuthorizationDelayed` 이벤트가 기록됨
- Order Service가 이벤트를 소비하고 Saga 상태가 `PAYMENT_DELAYED`로 전환됨
- 주문 상태는 `CREATED`로 유지되고 `OrderCancelled`/`OrderConfirmed`는 발행되지 않음

## 지연 결제 관리자 취소 시나리오

`DELAY_CARD` 주문에 대해 운영자가 결제 취소를 요청하고 재고가 복구되는지 확인한다.

```bash
curl -sS -X POST http://localhost:18080/api/admin/orders/"$DELAY_ORDER_ID"/cancel \
  -H 'Idempotency-Key: admin-cancel-delaycard-demo-001'
```

상태 조회:

```bash
curl -sS http://localhost:18080/api/orders/"$DELAY_ORDER_ID"
curl -sS http://localhost:18082/api/stocks/"$SKU_ID"
```

예상 결과:

- Order outbox에 `PaymentCancelRequested` command가 기록됨
- Payment Service가 `PaymentCanceled` 이벤트를 발행함
- Order Service가 `PaymentCanceled`를 소비하고 `OrderCancelled`를 발행함
- Inventory Service가 `OrderCancelled`를 소비해 예약 재고를 복구함
- 주문 상태는 `CANCELLED`, Saga 상태는 `FAILED`가 됨

## Admin App 확인 항목

Admin App에서 아래 화면을 순차적으로 확인한다.

- 상품 조회/관리: 상품 등록/수정 화면에서 데모 상품 존재 확인
- 재고 관리: `DEMO-001`의 SKU 재고 수량, 예약 수량 확인
- 주문 운영: 주문 목록에서 `CARD`/`FAIL_CARD`/`DELAY_CARD` 주문의 상태 표시와 지연 결제 취소 버튼 확인
- Saga 상세: 실패 주문 상세에서 실패 지점과 마지막 이벤트 확인
- Outbox 운영:
  - 주문/재고/결제 서비스별 outbox 이벤트 목록 조회
  - `PENDING`/`FAILED` 이벤트 노출
  - retry가 필요한 경우 수동 재시도 버튼 동작
  - failed 이벤트를 pending 재처리 대상으로 되돌리는 버튼 동작
  - retry/requeue 요청 후 서비스별 `outbox_admin_actions`에 operator id와 처리 건수가 남는지 확인

## Verification Checklist

- [ ] Docker 컨테이너가 모두 실행 중이며 Postgres, Kafka, Kafka UI가 정상 기동
- [ ] 18080~18087 모든 서비스 `actuator/health` 응답 200
- [ ] Customer App / Admin App 접속 가능
- [ ] `CARD` 주문에서 주문이 `CONFIRMED`/`COMPLETED` 전환
- [ ] `FAIL_CARD` 주문에서 주문이 `CANCELLED`/`FAILED` 전환
- [ ] `FAIL_CARD` 실행 후 SKU 재고가 결제 전 수량 기준으로 복구
- [ ] `DELAY_CARD` 주문에서 주문이 `CREATED`/`PAYMENT_DELAYED`로 유지
- [ ] 지연 결제 취소 요청 후 주문이 `CANCELLED`/`FAILED`로 전환되고 SKU 재고가 복구
- [ ] outbox에서 PENDING 이벤트가 장기 체류하지 않고 처리됨
- [ ] Kafka UI에서 관련 토픽 이벤트가 발행/소비되는지 확인

## Troubleshooting

- 서비스가 바로 시작되지 않거나 DB 연결 실패
  - `infra/local/.env`의 포트가 실제 컨테이너 포트와 일치하는지 확인
  - `docker compose logs -f`로 실패 원인 확인
- `Idempotency-Key` 누락
  - 고객/관리자 API에서 400 응답을 반환하므로 요청 헤더를 다시 확인
- 주문 상태가 `CREATED`에서 멈춤
  - 주문, 재고, 결제 outbox에서 `PENDING` 이벤트를 확인
  - Kafka 브로커/토픽 연결 및 컨슈머 로그 점검
- 프론트엔드에서 API 호출 실패(404/502)
  - Vite 개발 서버에서 백엔드 서비스 포트가 실제 실행 중인지 재확인
- 테스트/검증 데이터 충돌
  - Idempotency-Key를 시나리오별로 분리하고, 필요 시 상품코드·SKU를 새로 지정

## Current Limits

- 인증/권한은 공개 버전 범위 외로 처리되지 않았습니다.
- 지연 후 재시도 같은 운영 극한 케이스는 확장 단계로 남아 있습니다.
- 게이트웨이는 주문 생성/조회, 관리자 주문 조회/취소, Outbox 조회/재시도/requeue의 기본 진입점으로 유지됩니다.
