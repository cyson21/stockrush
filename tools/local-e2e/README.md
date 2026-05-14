# Local E2E Tools

이 디렉토리는 이미 기동된 StockRush 로컬 서비스에 대해 반복 가능한 E2E 확인을 수행하는 도구를 둔다.

## same-sku-concurrency

동일 SKU에 대해 여러 주문을 동시에 생성한 뒤, Gateway Outbox route로 서비스별 retry API를 호출해 최종 상태를 확인한다.

```bash
./tools/local-e2e/local-e2e same-sku-concurrency \
  --orders 6 \
  --initial-stock 3 \
  --quantity 1 \
  --max-attempts 12
```

기본 포트는 아래 서비스를 사용한다. 주문 생성/조회와 outbox 조회/재시도는 Gateway를 경유한다.

| Role | URL |
|---|---|
| Catalog | `http://localhost:18081` |
| Inventory | `http://localhost:18082` |
| Order API through Gateway | `http://localhost:18080` |
| Outbox API through Gateway | `http://localhost:18080` |
| Order health | `http://localhost:18083` |
| Payment | `http://localhost:18084` |
| Promotion admin | `http://localhost:18085` |

직접 Order Service 포트로 주문 생성/조회를 검증해야 하면 `--order-api-url http://localhost:18083`을 지정한다. Outbox 검증은 Gateway route shape를 기준으로 한다.

### 검증 기준

- 주문 생성 요청 6건을 같은 SKU로 병렬 호출한다.
- 초기 재고 3개 기준으로 `CONFIRMED/COMPLETED` 3건, `CANCELLED/FAILED` 3건을 기대한다.
- 최종 재고는 `availableQuantity=0`, `reservedQuantity=0`이어야 한다.
- 이번 실행으로 증가한 Order/Inventory/Payment `PENDING` outbox가 없어야 한다.

이 도구는 로컬 최종 상태 회귀 검증용이다. Kafka consumer 병렬성과 외부 부하 벤치마크까지 증명하는 테스트는 아니다. Broker 장애는 `kafka-outage-recovery` 명령으로 선택 실행한다.

## demo-order-flow

`CARD`, `FAIL_CARD`, `DELAY_CARD` 주문을 같은 데모 상품/SKU로 생성하고, `CARD` 주문에는 데모 쿠폰을 적용한다. 지연 결제 주문은 관리자 취소까지 진행한다.

```bash
./tools/local-e2e/local-e2e demo-order-flow \
  --initial-stock 20 \
  --quantity 1 \
  --max-attempts 12
```

`scripts/demo-smoke.sh`와 `scripts/demo-smoke.ps1`은 이 명령을 호출한 뒤 `burst-idempotency`를 이어서 실행해 Docker 데모 스택의 주문 흐름과 멱등 재시도 수렴을 함께 확인한다. 빠른 로컬 확인만 필요하면 smoke 스크립트에 `--skip-burst`를 지정한다.

### 검증 기준

- 실행마다 고유 상품/SKU와 고유 쿠폰을 생성한다.
- 데모 쿠폰은 Promotion Service 관리자 API로 생성하고, 쿠폰 견적은 Gateway의 `/api/coupons/quote` 경로로 확인한다.
- `CARD` 주문은 `CONFIRMED/COMPLETED`가 된다.
- `CARD` 주문 상세의 `couponCode`, `discountAmount`, `payableAmount`가 쿠폰 견적과 일치해야 한다.
- `FAIL_CARD` 주문은 `CANCELLED/FAILED`가 되고 재고가 복구된다.
- `DELAY_CARD` 주문은 `PAYMENT_DELAYED` 도달 후 관리자 취소로 `CANCELLED/FAILED`가 된다.
- 기본 초기 재고 20개, 수량 1개 기준 최종 재고는 `availableQuantity=19`, `reservedQuantity=0`이다.
- 이번 실행으로 증가한 Order/Inventory/Payment `PENDING` outbox가 없어야 한다.

## burst-idempotency

고부하 주문 요청에서 멱등 재시도 동작을 검증한다.

```bash
./tools/local-e2e/local-e2e burst-idempotency \
  --orders 30 \
  --initial-stock 10 \
  --idempotency-replays 2 \
  --relay-workers 4 \
  --stability-waves 2
```

### 검증 기준

- productCode는 실행마다 생성되는 값을 결과 증거로 기록한다.
- requestAttemptCount: `60`
- unique orderIds: `30`
- 주문 상태: `CONFIRMED/COMPLETED` 10건, `CANCELLED/FAILED` 20건, `unresolved` 0건
- 최종 재고는 `availableQuantity=0`, `reservedQuantity=0`이어야 한다.
- Order/Inventory/Payment `pendingOutboxDelta`와 `postReplayPendingOutboxDelta`는 모두 `0`이어야 한다.
- `--allow-existing-pending` 사용 시에도 이번 실행 이후 새로 남은 pending outbox event ID가 없어야 한다.

## outbox-recovery

Order/Inventory/Payment outbox의 `PENDING`/`FAILED` 장기 체류 row를 운영 API로 복구한다.

```bash
./tools/local-e2e/local-e2e outbox-recovery \
  --operator-id local-runbook \
  --max-attempts 3 \
  --wait-seconds 1
```

### 동작 기준

- Gateway의 `/api/admin/outbox-services/{service}/events` route로 `PENDING,FAILED` row를 조회한다.
- 기본값은 서비스별 failed requeue 후 pending retry를 실행한다.
- `--skip-requeue-failed`를 지정하면 pending retry만 수행하고, 남은 `FAILED` row는 결과 오류로 보고한다.
- `X-Operator-Id`와 `X-Correlation-Id`를 함께 전송해 서비스별 admin action audit row를 남긴다.
- `nextRetryAt`이 미래인 `PENDING` row는 deferred로 분류하고 실패로 보지 않는다.
- 최종 결과에서 `retryablePendingCounts`와 `failedCounts`가 모두 0이어야 성공이다.

## kafka-outage-recovery

데모 Docker Compose의 Kafka service를 일시 중지한 상태에서 주문을 생성하고, Kafka 복구 뒤 주문/재고/outbox가 수렴하는지 확인한다. 기본 smoke에는 포함하지 않고 장애 주입이 필요할 때만 실행한다.

```bash
./tools/local-e2e/local-e2e kafka-outage-recovery \
  --compose-file infra/demo/docker-compose.yml \
  --env-file infra/demo/.env \
  --kafka-service kafka \
  --relay-mode automatic \
  --outage-observation-seconds 2 \
  --max-attempts 30 \
  --wait-seconds 1
```

### 검증 기준

- `docker compose pause kafka` 구간에서 주문이 최종 완료로 즉시 수렴하지 않아야 한다.
- Kafka pause 구간에서 이번 실행으로 생긴 `PENDING`/`PUBLISHING`/`FAILED` outbox가 관측되어야 한다.
- `docker compose unpause kafka` 이후 주문은 `CONFIRMED/COMPLETED`가 되어야 한다.
- 최종 재고는 `availableQuantity=initialStock-quantity`, `reservedQuantity=0`이어야 한다.
- Order/Inventory/Payment `pendingOutboxDelta`와 신규 `PENDING`/`PUBLISHING`/`FAILED` outbox event ID가 남지 않아야 한다.

## 주의 사항

- 기본값은 실행 전 기존 `PENDING` outbox가 있으면 중단한다.
- `--allow-existing-pending`은 기존 대기 row를 허용하되, 실행 전후 증가분을 기준으로 실패 여부를 판단한다.
- product code와 sku id는 실행마다 `{prefix}-YYYYMMDDHHMMSS-xxxxxxxx` 형태로 생성한다.
- `kafka-outage-recovery`는 demo compose의 Kafka service를 `pause/unpause`하므로 다른 로컬 검증과 동시에 실행하지 않는다.
