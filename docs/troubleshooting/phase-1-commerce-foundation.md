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

## 검증 공백

### 6. Gateway 경유 E2E가 아직 없음

| 항목 | 내용 |
|---|---|
| 증상 | 현재 로컬 E2E는 서비스 포트 직접 호출 중심이라 Gateway 라우팅 오류를 놓칠 수 있음 |
| 원인 | Phase 1에서는 gateway를 healthcheck 중심의 얇은 진입점으로 두고, 핵심 주문 흐름을 먼저 완성함 |
| 보강 방향 | `POST /api/orders`, `GET /api/orders/{orderId}`를 Gateway 기준으로 호출하는 smoke test 추가 |
| 재발 방지 | README 검증 요약에 Gateway 기준 검증 여부를 별도 표기하고, gateway routing이 추가되면 runbook 예시를 gateway URL로 전환 |
| 근거 | [README](../../README.md), [Local E2E Runbook](../runbooks/local-e2e.md), [Gateway README](../../services/gateway/README.md), [Test Strategy](../test-strategy.md) |

### 7. 동시 주문 경합 자동 테스트가 아직 없음

| 항목 | 내용 |
|---|---|
| 증상 | 실제 트래픽이 몰리는 상황에서 과다 선점, 음수 재고, 중복 command window를 자동 테스트로 반복 재현하지 못함 |
| 원인 | 구현은 SKU별 수량 합산, row lock, 조건부 차감까지 적용했지만 명시적 race test는 후속 범위로 남김 |
| 보강 방향 | 동일 SKU에 대해 동시 주문을 여러 개 던지고 `availableQuantity`, `reservedQuantity`, 주문 결과 수를 함께 검증하는 회귀 테스트 추가 |
| 재발 방지 | 재고 선점 로직을 수정할 때마다 단일 주문 테스트와 동시 경합 테스트를 같이 실행 |

### 8. Kafka 장애와 장기 체류 Outbox 복구 자동화가 아직 부족함

| 항목 | 내용 |
|---|---|
| 증상 | Kafka broker 중단, relay 실패, 장기 `PENDING` 또는 `FAILED` outbox가 자동 회귀에서 충분히 검증되지 않음 |
| 원인 | Outbox relay의 retry/failed 전이는 테스트했지만 broker 장애 주입과 장애 후 복구를 정규 자동화까지 끌어올리지는 않음 |
| 보강 방향 | Kafka 중단 후 주문 생성, outbox 상태 확인, Kafka 재기동 후 relay 재실행, 최종 `PUBLISHED` 전환까지 검증하는 장애 주입 테스트 추가 |
| 재발 방지 | 운영 runbook에 broker 장애 시 확인 순서와 outbox admin API 사용 기준을 추가 |
| 근거 | [Outbox and Consumer Idempotency](../architecture/outbox.md), [Outbox Admin API](../api/outbox-admin.md), [Test Strategy](../test-strategy.md) |

### 9. `FAILED -> PENDING` 수동 복구 액션은 아직 제외됨

| 항목 | 내용 |
|---|---|
| 증상 | max retry를 모두 소진한 outbox row를 운영자가 즉시 재시도 가능 상태로 되돌리는 API가 없음 |
| 원인 | 첫 slice에서는 상태 직접 변경을 열지 않고, 기존 relay를 호출하는 bounded retry만 제공함 |
| 보강 방향 | 별도 운영 권한, 확인 절차, 감사 로그, 상태 전이 검사를 둔 뒤 `FAILED -> PENDING` 액션을 추가 |
| 재발 방지 | 수동 상태 변경 API는 일반 retry와 분리하고, 실패 원인과 재시도 횟수를 화면에서 같이 노출 |

### 10. 인증/권한은 현재 slice 밖에 있음

| 항목 | 내용 |
|---|---|
| 증상 | 공개 배포 기준에서는 고객 API와 관리자 API 접근 제어를 검증할 수 없음 |
| 원인 | 현재 목표가 Kafka/Saga/Outbox 기반 주문 흐름과 운영 복구 경로를 완성하는 것이었기 때문에 인증/권한은 후속 범위로 분리 |
| 보강 방향 | Gateway routing과 함께 인증/권한 middleware를 추가하고, 관리자 API는 권한 없는 요청과 권한 부족 요청을 별도 테스트로 고정 |
| 재발 방지 | 공개 배포 전 checklist에 인증/권한 테스트와 관리자 API 접근 제어 확인을 필수 항목으로 추가 |
| 근거 | [Outbox Admin API](../api/outbox-admin.md), [Test Strategy](../test-strategy.md), [README](../../README.md) |

## 면접에서 피해야 할 주장

- “모든 API 경로를 Gateway 기준으로 검증했다.”
  - 현재 증거는 서비스 포트 직접 호출과 Vite proxy 검증 중심이다.
- “동시 주문 경합은 완전히 증명됐다.”
  - row lock과 조건부 차감 구현은 있지만, 명시적 race test는 아직 남아 있다.
- “Kafka 장애가 나도 자동으로 항상 복구된다.”
  - Outbox retry/failed 전이는 검증했지만 broker 장애 주입 자동화는 아직 부족하다.
- “운영자가 실패 Outbox를 즉시 재처리 상태로 바꿀 수 있다.”
  - 현재는 due `PENDING` relay 재실행만 제공하고, `FAILED -> PENDING` 액션은 제외했다.
- “운영 배포 수준의 인증/권한이 포함돼 있다.”
  - 인증/권한은 현재 공개 slice 밖으로 명시했다.

## 다음 보강 순서

1. Gateway 기준 주문 생성/조회 smoke test 추가
2. 동일 SKU 동시 주문 경합 테스트 추가
3. Kafka 장애 주입과 outbox 장기 체류 복구 runbook 확장
4. `FAILED -> PENDING` 운영 액션 설계와 권한/감사 로그 추가
5. 관리자 API 인증/권한 테스트 추가
