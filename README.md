# StockRush

한정 판매 주문을 Order, Inventory, Payment로 분리했을 때 발생하는 부분 실패를 Saga와 Transactional Outbox로 수렴시키는 Java/Spring 프로젝트입니다. [웹 사례](https://cyson21.github.io/projects/stockrush/)

## 문제

주문 DB 반영, 재고 예약, 결제 승인, 이벤트 발행은 한 트랜잭션으로 묶을 수 없습니다. 중복 요청이나 중간 실패가 발생해도 재고가 초과 판매되지 않고, 주문과 결제 상태가 모순 없이 끝나며, 발행되지 않은 이벤트를 다시 처리할 수 있어야 합니다.

## 설계

```text
Client -> Gateway (OIDC/JWT, role, ownership)
       -> Order ---- Saga state + Outbox ---- Kafka
       -> Inventory reservation + Outbox --- Kafka
       -> Payment authorization + Outbox --- Kafka
Kafka  -> Fulfillment / Promotion / Read Model
각 서비스 -> PostgreSQL service schema + Flyway
```

- 외부 HTTP 진입점은 Gateway로 모으고 내부 서비스에는 검증된 사용자·역할·상관관계 ID를 전달합니다.
- 상태 변경과 Outbox 저장을 같은 로컬 트랜잭션에 기록하고 relay가 `PENDING` 이벤트를 선점해 발행합니다.
- 소비자는 event id를 기준으로 중복 처리를 차단하고, 관리자 API는 `PENDING`·`FAILED` 조회와 재시도를 제공합니다.

## 실패 조건

| 조건 | 보호 규칙 |
|---|---|
| 같은 idempotency key 재전송 | 주문은 한 건만 생성되어야 함 |
| 동일 SKU에 재고보다 많은 동시 주문 | 완료 수량은 초기 재고를 넘지 않고 예약 잔량은 정리되어야 함 |
| 결제 실패·지연·취소 | 주문 상태와 예약 재고가 보상 흐름을 거쳐 수렴해야 함 |
| Kafka 일시 중단 | DB 상태는 보존되고 broker 재개 후 미발행 Outbox가 전달되어야 함 |
| 고객의 관리자 경로·타인 주문 접근 | Gateway와 서비스 소유권 검사에서 거절되어야 함 |

## 검증 결과

| 검증 | 확인 결과 |
|---|---|
| 서비스 통합 테스트 | 주문 생성과 Outbox 저장, 재고 예약·해제, 결제 결과와 Saga 전이를 DB 경계에서 확인 |
| Local E2E | 정상·실패·지연 결제와 idempotency 재전송 뒤 주문·재고·서비스별 pending Outbox를 함께 판정 |
| 동일 SKU 동시성 | 완료·취소 건수뿐 아니라 `availableQuantity`, `reservedQuantity`, `pendingOutboxDelta`까지 검사 |
| Kafka outage smoke | 단일 broker pause 중 Outbox 대기를 관찰하고 unpause 뒤 신규 미처리 이벤트 없이 수렴하는지 확인 |
| Architecture Guard | 고객·관리자 외부 port와 Gateway-only 진입 경계를 정적 검사 |

## 대표 코드와 테스트

- 코드: [PersistentCreateOrderService](services/order-service/src/main/java/com/stockrush/order/application/PersistentCreateOrderService.java) - 주문과 Outbox 레코드를 한 트랜잭션에 저장합니다.
- 테스트: [PersistentCreateOrderServiceIntegrationTest](services/order-service/src/test/java/com/stockrush/order/infra/persistence/PersistentCreateOrderServiceIntegrationTest.java) - 중복 key와 영속 상태를 PostgreSQL 연결 경계에서 검증합니다.

## 실행

Docker Engine, Docker Compose v2, `curl`, Python 3가 필요합니다.

```bash
(
  set -e
  trap './scripts/demo-down.sh' EXIT
  ./scripts/demo-up.sh
  ./scripts/demo-smoke.sh
)
```

Kafka 중단 복구는 독립 실행합니다.

```bash
(
  set -e
  trap './scripts/demo-down.sh' EXIT
  ./scripts/demo-up.sh
  ./scripts/demo-smoke.sh --kafka-outage
)
```

동시 주문과 서비스별 테스트 절차는 [Local E2E Runbook](docs/runbooks/local-e2e.md), 검증 계층은 [Test Strategy](docs/test-strategy.md)를 따릅니다.

## 제한 사항

- 결제와 출고는 내부 시뮬레이션이며 외부 PG·물류 시스템과 연동하지 않았습니다.
- 검증은 고정된 로컬 기능 시나리오입니다. 운영 규모 부하, 처리량, 지연시간, SLO를 측정하지 않았습니다.
- Kafka 훈련은 단일 broker `pause/unpause`입니다. 다중 broker 장애, partition 재할당, 장기 outage 복구는 포함하지 않았습니다.
- 관리자 복구 API는 로컬 시뮬레이션이며 실제 조직의 승인·감사·운영 권한 체계를 구현하지 않았습니다.
- kind 구성은 로컬 재현 경로이며 운영 Kubernetes 가용성 증거가 아닙니다.
