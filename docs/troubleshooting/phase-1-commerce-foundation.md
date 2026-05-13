# Phase 1 Commerce Foundation Troubleshooting

이 문서는 StockRush Phase 1/2 구현 중 확인한 문제와, 현재 의도적으로 남긴 검증 공백을 면접에서 설명할 수 있는 형태로 정리한다.

## 읽는 기준

- `해결 완료`: 구현 중 실제로 정리했고 현재 문서나 테스트에 반영된 항목
- `검증 공백`: 현재 공개 slice에서 의도적으로 남긴 항목이며, 다음 phase에서 보강할 항목
- `재발 방지`: 같은 문제가 다시 생기지 않도록 문서, 테스트, 가드, 운영 화면 중 어디에 묶을지 적는다

## 해결 완료

### 1. 주문 schema 이름이 SQL 예약어와 충돌할 수 있음

| 항목 | 내용 |
|---|---|
| 증상 | `order`를 schema 또는 식별자로 쓰면 SQL 예약어와 겹쳐 quoting 비용과 이식성 문제가 생길 수 있음 |
| 원인 | 주문 도메인 이름을 그대로 DB schema 이름으로 옮기려는 초기 설계 |
| 해결 | 주문 서비스 schema를 `orders`로 확정하고, 문서와 Architecture Guard 기준도 `orders`에 맞춤 |
| 재발 방지 | 신규 서비스 schema를 만들 때 예약어 여부를 먼저 확인하고, Architecture Guard의 schema ownership 기준에 반영 |

### 2. Spring Boot 4 환경에서 Flyway PostgreSQL 의존성이 부족함

| 항목 | 내용 |
|---|---|
| 증상 | PostgreSQL 통합 테스트에서 migration 초기화가 바로 진행되지 않음 |
| 원인 | Flyway core만으로는 현재 Spring Boot 4 조합에서 PostgreSQL database module 구성이 충분하지 않음 |
| 해결 | `spring-boot-starter-flyway`와 `flyway-database-postgresql` 조합으로 정리하고 PostgreSQL 16 컨테이너에서 migration을 검증 |
| 재발 방지 | 서비스 스캐폴딩 시 Flyway starter와 DB별 Flyway module을 같이 확인하고, 첫 persistence 작업에서 실제 DB 통합 테스트를 실행 |

### 3. PostgreSQL timestamp 바인딩 방식이 흔들릴 수 있음

| 항목 | 내용 |
|---|---|
| 증상 | `Instant` 값을 그대로 JDBC parameter로 전달할 때 PostgreSQL 타입 추론 문제가 생길 수 있음 |
| 원인 | DB column은 `timestamptz`인데 Java 시간 타입 바인딩이 명시적이지 않은 구간이 있었음 |
| 해결 | audit timestamp는 DB `now()` 또는 명시적 timestamp 처리 기준을 사용하도록 정리 |
| 재발 방지 | repository 테스트에서 insert/update SQL을 실제 PostgreSQL로 검증하고, 시간 column은 서비스별 동일 패턴을 유지 |

### 4. 관리자 재시도 요청에서 멱등 키가 매번 새로 만들어질 수 있음

| 항목 | 내용 |
|---|---|
| 증상 | 같은 관리자 작업을 재시도할 때 매번 새로운 `Idempotency-Key`가 생성되면, 동일한 논리 요청인지 서버가 구분하기 어려움 |
| 원인 | UI click 단위로 command key를 생성하면 네트워크 실패 후 재시도와 새 요청이 섞임 |
| 해결 | Admin App 상품 등록/수정과 지연 결제 취소 요청에서 동일 입력 재시도 시 같은 멱등 키를 재사용하도록 보강 |
| 재발 방지 | command 성격 API는 UI 테스트에서 `Idempotency-Key` 생성/재사용 기준을 확인하고, API 문서에 필수 헤더를 유지 |

### 5. 관리자 앱 모바일 폭에서 탭 텍스트가 잘릴 수 있음

| 항목 | 내용 |
|---|---|
| 증상 | 320px 폭에서 관리자 앱 탭이 가로 스크롤로 유지되며 일부 탭 텍스트가 잘림 |
| 원인 | 주문, Outbox, 상품/재고 탭을 desktop 기준으로 먼저 잡아 좁은 화면의 고정 폭을 충분히 고려하지 못함 |
| 해결 | 390px 이하에서 3-column tab layout으로 전환하고 320px 화면까지 확인 |
| 재발 방지 | 운영 화면 변경 시 desktop, 390px, 320px 화면을 같이 확인하고 text overflow를 별도 체크 |

### 6. 지연 결제 취소 E2E가 stale 이벤트에 오염될 수 있음

| 항목 | 내용 |
|---|---|
| 증상 | 오래된 Kafka topic 메시지나 due `PENDING` outbox가 남아 있으면 새 지연 결제 E2E 주문이 이전 주문 이벤트와 섞여 상태 전이가 늦거나 실패할 수 있음 |
| 원인 | 수동 retry API는 aggregate별 필터 없이 due `PENDING` outbox를 `created_at` 순서로 발행하고, 고정 consumer group은 이전 topic offset을 이어받음 |
| 해결 | listener 비활성 상태에서 기존 due `PENDING` outbox를 배출한 뒤, 새 consumer group과 `latest` offset으로 서비스를 재기동해 `ord_20260513012031_8c06cd49` 흐름을 검증 |
| 재발 방지 | 로컬 E2E 전 PENDING outbox 확인, fresh consumer group 사용, 지연 결제 주문 id/SKU/멱등 키 분리 절차를 runbook 운영 습관으로 유지 |
| 근거 | [README](../../README.md), [Test Strategy](../test-strategy.md), [Local E2E Runbook](../runbooks/local-e2e.md) |

### 7. Order Service가 비-Saga 이벤트를 retry할 수 있음

| 항목 | 내용 |
|---|---|
| 증상 | Gateway E2E 중 Order Service Kafka listener에서 `unsupported inventory event` 로그가 반복됨 |
| 원인 | Inventory Service가 같은 inventory event topic에 `InventoryReservationConfirmed`/`InventoryReservationReleased`를 발행하지만, Order Service consumer가 Saga 입력인 `InventoryReserved`/`InventoryReservationFailed` 외 eventType을 예외로 처리함 |
| 해결 | Order Service inventory/payment consumer에서 valid but irrelevant eventType은 debug log 후 무시하고, JSON 파싱 실패는 기존처럼 예외로 유지 |
| 재발 방지 | consumer integration test에서 비-Saga inventory/payment event가 주문 상태, processed event, outbox를 변경하지 않는지 검증 |

## 검증 공백

### 8. Gateway 경유 운영 API는 아직 부분적임

| 항목 | 내용 |
|---|---|
| 증상 | Gateway 주문 생성/조회와 관리자 주문 조회/취소 라우팅 smoke, 동일 SKU runner, runbook의 주문 생성/조회/취소 경로는 Gateway를 경유하지만 Outbox admin API는 서비스 포트 직접 호출 중심임 |
| 원인 | Order Service의 주문 API는 단일 upstream으로 proxy하기 쉽지만, Outbox admin API는 Order/Inventory/Payment Service가 같은 path를 서비스별로 소유해 Gateway에 service 식별 설계가 추가로 필요함 |
| 보강 방향 | Outbox admin API를 Gateway에서 다룰 service 식별 방식과 권한 기준을 정한 뒤 실제 서비스 조합으로 재검증 |
| 재발 방지 | README 검증 요약에 Gateway 경유 주문 API와 서비스별 직접 Outbox admin API를 분리 표기 |
| 근거 | [README](../../README.md), [Local E2E Runbook](../runbooks/local-e2e.md), [Gateway README](../../services/gateway/README.md), [Test Strategy](../test-strategy.md) |

### 9. 동시 주문 부하/consumer 병렬성 검증은 아직 부족함

| 항목 | 내용 |
|---|---|
| 증상 | 서비스 단위 동일 SKU 경합은 회귀 테스트로 고정했고 로컬 서비스 최종 상태 E2E runner도 추가했지만, Kafka consumer 병렬성이나 외부 부하 수준의 경합 검증은 아직 부족함 |
| 원인 | 현재 runner는 주문 API를 병렬 호출한 뒤 outbox retry를 순차 실행해 최종 상태를 확인한다. 이는 로컬 회귀 검증에는 유효하지만 부하 벤치마크나 consumer 병렬 처리 증거는 아님 |
| 보강 방향 | inventory listener concurrency 설정, SKU key 기반 파티셔닝 전략, 반복 부하 도구, consumer lag/처리량 지표를 묶은 별도 부하 검증 추가 |
| 재발 방지 | 문서에서 `same-sku-concurrency`를 로컬 최종 상태 E2E로만 설명하고, 부하/병렬성 검증은 별도 TODO로 추적 |

### 10. Kafka 장애와 장기 체류 Outbox 복구 자동화가 아직 부족함

| 항목 | 내용 |
|---|---|
| 증상 | Kafka broker 중단, relay 실패, 장기 `PENDING` 또는 `FAILED` outbox가 자동 회귀에서 충분히 검증되지 않음 |
| 원인 | Outbox relay의 retry/failed 전이는 테스트했지만 broker 장애 주입과 장애 후 복구를 정규 자동화까지 끌어올리지는 않음 |
| 보강 방향 | Kafka 중단 후 주문 생성, outbox 상태 확인, Kafka 재기동 후 relay 재실행, 최종 `PUBLISHED` 전환까지 검증하는 장애 주입 테스트 추가 |
| 재발 방지 | 운영 runbook에 broker 장애 시 확인 순서와 outbox admin API 사용 기준을 추가 |
| 근거 | [Outbox and Consumer Idempotency](../architecture/outbox.md), [Outbox Admin API](../api/outbox-admin.md), [Test Strategy](../test-strategy.md) |

### 11. `FAILED -> PENDING` 수동 복구 액션은 아직 제외됨

| 항목 | 내용 |
|---|---|
| 증상 | max retry를 모두 소진한 outbox row를 운영자가 즉시 재시도 가능 상태로 되돌리는 API가 없음 |
| 원인 | 첫 slice에서는 상태 직접 변경을 열지 않고, 기존 relay를 호출하는 bounded retry만 제공함 |
| 보강 방향 | 별도 운영 권한, 확인 절차, 감사 로그, 상태 전이 검사를 둔 뒤 `FAILED -> PENDING` 액션을 추가 |
| 재발 방지 | 수동 상태 변경 API는 일반 retry와 분리하고, 실패 원인과 재시도 횟수를 화면에서 같이 노출 |

### 12. 인증/권한은 현재 slice 밖에 있음

| 항목 | 내용 |
|---|---|
| 증상 | 공개 배포 기준에서는 고객 API와 관리자 API 접근 제어를 검증할 수 없음 |
| 원인 | 현재 목표가 Kafka/Saga/Outbox 기반 주문 흐름과 운영 복구 경로를 완성하는 것이었기 때문에 인증/권한은 후속 범위로 분리 |
| 보강 방향 | Gateway routing과 함께 인증/권한 middleware를 추가하고, 관리자 API는 권한 없는 요청과 권한 부족 요청을 별도 테스트로 고정 |
| 재발 방지 | 공개 배포 전 checklist에 인증/권한 테스트와 관리자 API 접근 제어 확인을 필수 항목으로 추가 |
| 근거 | [Outbox Admin API](../api/outbox-admin.md), [Test Strategy](../test-strategy.md), [README](../../README.md) |

## 면접에서 피해야 할 주장

- “모든 API 경로를 Gateway 기준으로 검증했다.”
  - 주문 생성/조회와 관리자 주문 조회/취소는 Gateway 기준으로 검증했지만, Outbox admin API는 아직 서비스 포트 직접 호출 기준이다.
- “동시 주문 경합은 상용 부하까지 검증됐다.”
  - 서비스 단위 동일 SKU race test와 로컬 최종 상태 E2E runner는 있지만, 부하 벤치마크와 Kafka consumer 병렬성 검증은 아직 남아 있다.
- “Kafka 장애가 나도 자동으로 항상 복구된다.”
  - Outbox retry/failed 전이는 검증했지만 broker 장애 주입 자동화는 아직 부족하다.
- “운영자가 실패 Outbox를 즉시 재처리 상태로 바꿀 수 있다.”
  - 현재는 due `PENDING` relay 재실행만 제공하고, `FAILED -> PENDING` 액션은 제외했다.
- “운영 배포 수준의 인증/권한이 포함돼 있다.”
  - 인증/권한은 현재 공개 slice 밖으로 명시했다.

## 다음 보강 순서

1. Outbox admin API의 Gateway 경유 방식 설계
2. 동일 SKU 부하 벤치마크와 Kafka consumer 병렬성 검증 추가
3. Kafka 장애 주입과 outbox 장기 체류 복구 runbook 확장
4. `FAILED -> PENDING` 운영 액션 설계와 권한/감사 로그 추가
5. 관리자 API 인증/권한 테스트 추가
