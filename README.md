# StockRush

[![CI](https://github.com/cyson21/stockrush/actions/workflows/ci.yml/badge.svg)](https://github.com/cyson21/stockrush/actions/workflows/ci.yml)

주문, 재고, 결제가 서비스별로 나뉜 쇼핑몰 백엔드입니다. 결제가 실패하거나 같은 이벤트가 두 번 오거나 Kafka가 잠깐 멈춰도, 주문이 어중간한 상태로 남지 않도록 Saga와 Transactional Outbox로 처리했습니다. 설계부터 구현, 테스트까지 혼자 진행한 개인 프로젝트입니다.

[포트폴리오](https://cyson21.github.io/projects/stockrush/) · [이력서](https://github.com/cyson21/portfolio-hub/releases/download/latest/resume.pdf)

## 풀려던 문제

주문 저장, 재고 예약, 결제 승인, 이벤트 발행은 한 트랜잭션으로 묶을 수 없습니다. 그래서 중간에 하나만 실패해도 재고는 빠졌는데 주문은 대기 중인 식으로 데이터가 어긋납니다. 요청이 중복되거나 중간에 실패해도 재고가 초과 판매되지 않고, 주문과 결제가 같은 결론으로 끝나고, 못 보낸 이벤트는 나중에 다시 보낼 수 있어야 했습니다.

취소한 주문에 결제 승인이 뒤늦게 도착하는 경우는 처음부터 막고 들어갔습니다. 실무에서 외부 콜백이 늦게 들어와 이미 취소된 데이터를 다시 바꿔 놓은 일을 겪은 적이 있어서, 주문 상태를 바꾸는 UPDATE에 "이미 끝난 주문은 제외" 조건을 넣었습니다.

## 구조

```text
Client -> Gateway (OIDC/JWT, role, ownership)
       -> Order ---- Saga state + Outbox ---- Kafka
       -> Inventory reservation + Outbox --- Kafka
       -> Payment authorization + Outbox --- Kafka
Kafka  -> Fulfillment / Promotion / Read Model
각 서비스 -> PostgreSQL service schema + Flyway
```

- 외부 요청은 모두 Gateway로 들어옵니다. 내부 서비스에는 확인된 사용자, 역할, 추적용 ID를 넘깁니다.
- 상태를 바꿀 때 보낼 이벤트도 같은 트랜잭션으로 Outbox 테이블에 넣고, relay가 `PENDING` 이벤트를 가져가 발행합니다.
- 소비자는 이미 처리한 event id면 건너뜁니다. 관리자 API로 `PENDING`, `FAILED` 이벤트를 보고 다시 보낼 수 있습니다.

## 실패 상황별 결과

| 상황 | 결과 |
|---|---|
| 같은 멱등 키로 다시 요청 | 주문은 한 건만 생깁니다 |
| 재고보다 많은 동시 주문 | 완료 수량이 처음 재고를 넘지 않고, 남은 예약도 정리됩니다 |
| 결제 실패, 지연, 취소 | 보상 흐름을 거쳐 주문은 취소, 예약 재고는 복구됩니다 |
| Kafka 일시 중단 | DB 데이터는 그대로 남고, Kafka가 살아나면 밀린 이벤트가 나갑니다 |
| 고객이 관리자 API나 남의 주문에 접근 | Gateway와 서비스 양쪽에서 거절됩니다 |

## 확인한 방법

| 검증 | 확인한 내용 |
|---|---|
| 서비스 통합 테스트 | 주문과 Outbox 저장, 재고 예약과 해제, 결제 결과에 따른 Saga 전이를 PostgreSQL에서 확인 |
| 로컬 통합 시나리오 | 정상, 실패, 지연 결제와 멱등 키 재전송 뒤에 주문, 재고, 서비스별 대기 Outbox를 함께 확인 |
| 같은 SKU 동시 주문 | 완료, 취소 건수와 `availableQuantity`, `reservedQuantity`, `pendingOutboxDelta`까지 확인 |
| Kafka 중단 복구 | broker를 멈춘 동안 Outbox가 쌓이고, 재개 뒤에 밀린 이벤트 없이 끝나는지 확인 |
| 외부 진입 규칙 | 고객, 관리자 API가 Gateway로만 들어오는지 정적 검사 |

## 대표 코드와 테스트

- 코드: [PersistentCreateOrderService](services/order-service/src/main/java/com/stockrush/order/application/PersistentCreateOrderService.java) - 주문과 Outbox를 한 트랜잭션에 저장합니다.
- 테스트: [PersistentCreateOrderServiceIntegrationTest](services/order-service/src/test/java/com/stockrush/order/infra/persistence/PersistentCreateOrderServiceIntegrationTest.java) - 중복 키가 들어왔을 때 PostgreSQL에 무엇이 남는지 확인합니다.

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

Kafka 중단 복구는 따로 실행합니다.

```bash
(
  set -e
  trap './scripts/demo-down.sh' EXIT
  ./scripts/demo-up.sh
  ./scripts/demo-smoke.sh --kafka-outage
)
```

동시 주문과 서비스별 테스트 방법은 [Local E2E Runbook](docs/runbooks/local-e2e.md), 테스트 구성은 [Test Strategy](docs/test-strategy.md)에 있습니다.

## 해 보지 않은 것

- 결제와 출고는 내부 시뮬레이션입니다. 실제 PG나 물류 시스템과는 연동하지 않았습니다.
- 로컬에서 정해진 시나리오만 돌려 봤습니다. 대규모 트래픽, 처리량, 지연 시간은 재지 않았습니다.
- Kafka는 브로커 하나를 멈췄다 살리는 것까지만 확인했습니다. 여러 브로커 장애나 오래 멈춘 경우는 해 보지 않았습니다.
- 관리자 복구 API에는 실제 회사처럼 승인이나 감사 절차가 없습니다.
- kind 구성은 로컬 재현용이고, 운영 Kubernetes에서 돌려 본 것은 아닙니다.
