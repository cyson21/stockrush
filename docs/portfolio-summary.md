# Portfolio Summary

이 문서는 StockRush를 이력서와 면접에서 설명하기 위한 요약본이다. 상세 구현 기준은 `README.md`, `docs/architecture/phase-1-commerce-foundation.md`, `docs/test-strategy.md`, `docs/ai-development-process.md`를 기준으로 한다.

## 한 줄 요약

StockRush는 한정 판매 커머스 주문 흐름을 MSA, Apache Kafka, Outbox, Saga로 구현하고, 고객 앱과 관리자 앱에서 성공/실패/지연 결제 운영 시나리오까지 확인할 수 있게 만든 백엔드 중심 포트폴리오 프로젝트다.

## 1분 설명

StockRush는 한정 판매 상황에서 주문, 재고, 결제가 분산 서비스로 나뉘었을 때 어떤 문제가 생기는지 보여주기 위해 만든 프로젝트입니다.

Order Service가 Saga Orchestrator 역할을 맡고, Inventory Service와 Payment Service는 Kafka 이벤트와 command를 통해 재고 선점, 결제 승인, 실패 복구, 지연 결제 취소 흐름을 처리합니다. 각 서비스는 Outbox와 processed event 저장으로 발행 안정성과 중복 처리 안전성을 확보했습니다.

겉으로는 고객 앱에서 상품 조회, 주문 생성, 주문 상태 추적을 할 수 있고, 관리자 앱에서는 상품/재고 관리, Saga 상태 확인, Outbox 재시도, 지연 결제 취소를 확인할 수 있습니다. 개발 과정에서는 Dev RAG, Project MCP, Spark worker/reviewer, Agent Runner, Architecture Guard를 활용해 AI 기반 개발 운영 흐름도 함께 구축했습니다.

## 3분 설명

StockRush의 핵심 문제는 “한정 판매 주문에서 재고와 결제를 한 트랜잭션으로 묶을 수 없을 때 어떻게 일관성 있는 사용자 경험과 운영 복구 경로를 만들 것인가”입니다.

서비스는 gateway, catalog-service, inventory-service, order-service, payment-service로 나눴습니다. 초기 로컬 환경은 단일 PostgreSQL 인스턴스 안에 서비스별 schema를 분리했고, 서비스 간 흐름은 Apache Kafka topic으로 연결했습니다. Order Service는 주문 상태와 Saga 상태를 관리하고, Inventory Service는 주문 이벤트를 받아 SKU별 재고를 선점하거나 실패 이벤트를 발행합니다. Payment Service는 `CARD`, `FAIL_CARD`, `DELAY_CARD` 결제수단으로 승인, 실패, 지연 상황을 시뮬레이션합니다.

신뢰성 측면에서는 모든 Kafka 발행을 서비스 로컬 Outbox에서 시작하게 했습니다. 이벤트 발행 실패는 retry/failed 상태로 남기고, 관리 화면에서 조회와 수동 재시도를 할 수 있습니다. Consumer는 processed event를 저장해 같은 메시지가 다시 들어와도 중복 처리를 피하도록 했습니다. 재고 선점은 SKU별 요청 수량을 합산한 뒤 PostgreSQL row lock과 조건부 차감으로 과다 선점을 막는 방향으로 구현했습니다.

운영 화면도 함께 만들었습니다. 고객 앱은 상품 목록, 상품 선택, SKU 재고 확인, 주문 생성, 주문 상태 polling을 제공하고, 관리자 앱은 상품 등록/수정, SKU 재고 설정, 주문/Saga 추적, Outbox 조회/재시도, 지연 결제 취소를 제공합니다. 특히 `DELAY_CARD` 주문은 관리자가 취소 요청을 보내면 `PaymentCancelRequested` command와 `PaymentCanceled` 이벤트를 거쳐 주문 취소와 재고 복구로 이어지게 했습니다.


## 10분 설명 흐름

### 1. 문제 정의

한정 판매 커머스에서는 짧은 시간에 주문 요청이 몰리고, 주문 생성, 재고 선점, 결제 승인 중 어느 단계든 실패할 수 있습니다. 단일 서버에서는 DB 트랜잭션 하나로 묶기 쉽지만, 서비스가 분리되면 재고와 결제를 즉시 함께 확정할 수 없습니다.

이 프로젝트는 이 상황에서 다음 질문에 답하는 것을 목표로 했습니다.

- 주문은 언제 생성 상태로 보여줄 것인가
- 재고 선점 실패와 결제 실패를 어떻게 주문 상태로 되돌릴 것인가
- Kafka 발행 실패나 중복 수신이 생겨도 어떻게 복구 가능하게 만들 것인가
- 개발 과정에서 AI가 만든 변경을 어떻게 검증 가능한 결과로 남길 것인가

### 2. 서비스 경계

| 서비스 | 책임 |
|---|---|
| gateway | 로컬 진입점과 헬스체크 |
| catalog-service | 상품 목록, 상품 상세, 관리자 상품 등록/수정 |
| inventory-service | SKU 재고 조회/설정, 주문 이벤트 기반 재고 선점/확정/복구 |
| order-service | 주문 생성/조회, Saga 상태 전이, 관리자 주문 운영, payment command 발행 |
| payment-service | 결제 승인/실패/지연/취소 시뮬레이션과 결제 이벤트 발행 |

서비스별 DB 소유권을 지키기 위해 PostgreSQL schema를 분리했고, 서비스 간 데이터 변경은 Kafka 이벤트와 command로 연결했습니다.

### 3. 주문 Saga 흐름

`CARD` 성공 흐름은 아래 순서로 진행됩니다.

1. 고객 앱이 주문 생성 API를 호출한다.
2. Order Service가 주문과 `OrderCreated` outbox event를 같은 DB 트랜잭션으로 저장한다.
3. Order Outbox Relay가 `stockrush.order.events.v1` topic에 이벤트를 발행한다.
4. Inventory Service가 이벤트를 소비해 재고를 선점하고 `InventoryReserved` event를 남긴다.
5. Order Service가 `InventoryReserved`를 받아 `PaymentAuthorizationRequested` command를 발행한다.
6. Payment Service가 command를 처리하고 `PaymentAuthorized` event를 남긴다.
7. Order Service가 주문을 `CONFIRMED`, Saga를 `COMPLETED`로 전환하고 `OrderConfirmed`를 발행한다.
8. Inventory Service가 `OrderConfirmed`를 받아 예약 재고를 확정한다.

실패 흐름에서는 `InventoryReservationFailed` 또는 `PaymentAuthorizationFailed`가 들어오면 Order Service가 주문을 `CANCELLED`, Saga를 `FAILED`로 전환하고 `OrderCancelled`를 발행합니다. Inventory Service는 `OrderCancelled`를 받아 예약 재고를 복구합니다.

지연 흐름에서는 `DELAY_CARD` 결제가 `PaymentAuthorizationDelayed`로 남고, 관리자 취소 요청이 들어오면 `PaymentCancelRequested` command와 `PaymentCanceled` event를 거쳐 같은 취소/복구 흐름으로 합류합니다.

### 4. 신뢰성 설계

Outbox는 Kafka 발행을 DB 저장과 분리해 장애 상황을 운영자가 복구할 수 있게 하는 장치입니다. 주문, 재고, 결제 서비스는 각각 outbox table을 가지고, relay는 PENDING 이벤트를 claim한 뒤 발행 성공 시 PUBLISHED로, 실패 시 retry 또는 FAILED로 전환합니다.

Consumer 멱등성은 같은 이벤트가 여러 번 들어와도 결과가 흔들리지 않게 하는 장치입니다. 각 consumer는 처리한 event id를 processed event로 저장하고, 이미 처리한 이벤트면 부작용을 다시 만들지 않습니다.

재고 선점에서는 같은 SKU가 한 주문 안에 여러 줄로 들어오거나 동시 주문이 들어올 수 있습니다. 이를 위해 요청 수량을 SKU별로 합산하고, DB row lock과 조건부 차감으로 `availableQuantity`가 음수가 되지 않도록 했습니다.

### 5. 운영 화면

고객 앱은 포트폴리오 시연에서 사용자가 흐름을 이해하기 위한 진입점입니다. 상품을 선택하고 SKU 재고를 확인한 뒤 `CARD`, `FAIL_CARD`, `DELAY_CARD` 결제수단으로 주문을 생성할 수 있습니다. 주문 생성 후에는 주문 조회 API를 polling해 상태 변화를 보여줍니다.

관리자 앱은 운영 복구 경로를 보여줍니다. 상품 등록/수정, SKU 재고 설정, 주문 목록과 Saga 상세 조회, 서비스별 Outbox 조회/재시도, 지연 결제 취소 요청을 한 화면 흐름으로 연결했습니다.

### 6. 검증 전략

검증은 계층별로 나눴습니다.

- Backend: 서비스별 Maven 테스트로 API, Saga handler, Outbox relay, Kafka smoke를 검증
- Frontend: 고객/관리자 앱 Vitest와 production build로 API 호출 모양과 상태 렌더링 검증
- Gateway: 주문 생성/조회 라우팅 smoke로 path, header, body, status, `Location` 전달을 검증
- Architecture Guard: schema ownership, Controller 반환 타입, event envelope, outbox table shape 점검
- Local E2E: `CARD`, `FAIL_CARD`, `DELAY_CARD`, 지연 결제 취소, Gateway 경유 동일 SKU 최종 상태를 실제 로컬 서비스와 Kafka로 확인

문서에는 구현된 검증과 남은 공백을 분리했습니다. 현재 동일 SKU runner와 runbook의 주문 생성/조회/취소 경로는 Gateway를 경유하지만, Outbox admin API는 서비스 포트를 직접 호출합니다. 인증/권한, Kafka 장애 복구 자동화, 부하 벤치마크와 Kafka consumer 병렬성 검증은 후속 확장 범위로 남아 있습니다.

### 7. AI 개발 운영

이 프로젝트에서 AI 활용은 산출물 자체보다 운영 방식이 중요합니다.

- Dev RAG로 ADR, API 문서, 실행 기록을 검색 가능한 문맥으로 관리
- Project MCP로 문서 조회와 검증 명령 조회를 표준화
- Spark worker/reviewer를 분리해 구현과 리뷰 책임을 나눔
- Architecture Guard로 사람이 놓치기 쉬운 서비스 경계와 이벤트 규칙을 자동 점검

면접에서는 “AI로 빠르게 만들었다”보다 “AI가 만든 변경을 어떤 문맥과 검증 체계 안에서 통제했는지”를 강조한다.

### 8. 의도적으로 남긴 확장 지점

현재 프로젝트는 완성된 상용 서비스보다 이직용 포트폴리오의 설명력을 우선한다. 그래서 핵심 흐름은 끝까지 연결하되, 아래 항목은 후속 확장으로 남겼다.

- Promotion Service: 쿠폰 적용과 실패 복구
- Fulfillment Service: 주문 완료 후 출고 요청
- Read Model: 관리자 대시보드와 고객 주문 내역 최적화
- Observability: OpenTelemetry, Prometheus, Grafana
- Security: 인증/권한과 관리자 권한 분리
- Resilience Test: Kafka 장애, consumer lag, 부하 벤치마크와 consumer 병렬성 검증

## 이력서 Bullet 후보

- Apache Kafka, Outbox, Saga 기반 한정 판매 주문 흐름을 MSA 구조로 구현하고, 결제 실패와 지연 결제 취소의 주문/재고 복구 흐름을 로컬 E2E로 검증
- Order Service를 Saga Orchestrator로 설계해 `InventoryReserved`, `PaymentAuthorized`, `PaymentAuthorizationFailed`, `PaymentCanceled` 이벤트에 따른 주문 상태 전이를 구현
- 서비스별 Outbox relay와 processed event 저장을 적용해 Kafka 발행 실패 재시도와 consumer 중복 처리 안전성을 확보
- 고객 앱과 관리자 앱을 함께 구현해 상품 조회, 주문 생성, 주문 상태 추적, 상품/재고 관리, Outbox retry, 지연 결제 취소 운영 흐름을 시연 가능하게 구성
- Dev RAG, Project MCP, Spark worker/reviewer, Agent Runner, Architecture Guard를 결합해 AI 기반 개발 운영과 검증 증적을 프로젝트 산출물로 정리
- Maven, Vitest, production build, Architecture Guard, Gateway 경유 동일 SKU runner, 로컬 E2E runbook으로 테스트 계층과 검증 절차를 문서화

## 면접 질문 대비

### 왜 MSA를 선택했나

단순 CRUD보다 5년차 백엔드 경험을 보여주려면 서비스 분리 이후 생기는 일관성, 장애 복구, 운영 가시성 문제를 다룰 필요가 있었다. 한정 판매 도메인은 주문, 재고, 결제 경계가 자연스럽고 Kafka/Saga/Outbox를 설명하기 좋기 때문에 MSA로 구성했다.

### 왜 Kafka를 선택했나

실무에서 널리 쓰이는 도구이고, 주문 이벤트와 결제 command처럼 비동기 흐름을 설명하기 좋다. 포트폴리오에서는 Kafka 호환 도구보다 Apache Kafka 자체를 사용해 broker, topic, consumer, relay 동작을 직접 확인하는 쪽을 선택했다.

### Outbox를 왜 넣었나

주문 저장과 Kafka 발행을 하나의 외부 트랜잭션으로 묶기 어렵기 때문이다. DB에는 주문과 outbox event를 같이 저장하고, relay가 나중에 Kafka로 발행하게 하면 발행 실패가 운영 가능한 상태로 남는다.

### Saga Orchestrator 방식의 장점은 무엇인가

주문 상태의 최종 책임을 Order Service에 둘 수 있다. 재고와 결제 서비스는 자기 책임 범위의 결과 이벤트를 발행하고, Order Service가 그 결과를 모아 주문 상태와 다음 command를 결정한다.

### 중복 메시지는 어떻게 처리했나

Consumer가 처리한 event id를 processed event로 저장한다. 이미 처리한 event id가 다시 들어오면 재고 차감, 결제 생성, outbox 기록 같은 부작용을 반복하지 않는다.

### 재고 과다 선점은 어떻게 막았나

주문 요청의 SKU별 수량을 먼저 합산하고, 재고 row를 잠근 상태에서 조건부로 available 수량을 차감한다. 이 방식은 동일 SKU 동시 주문 회귀 테스트와 로컬 최종 상태 E2E runner로 검증해, 한정된 재고보다 많이 예약되지 않도록 했다.

### AI 활용에서 차별점은 무엇인가

AI를 단순 코드 생성기로 쓰지 않고, Dev RAG와 MCP로 문맥을 고정하고, Spark worker/reviewer로 역할을 분리하고, Agent Runner와 Architecture Guard로 실행 증거와 정적 검증을 남겼다. 그래서 변경 이유와 검증 근거를 문서와 테스트로 설명할 수 있다.

### 지금 가장 큰 한계는 무엇인가

인증/권한, Outbox admin API의 Gateway 경유 전환, 관측성, 장애 복구 자동화는 아직 후속 범위다. 현재 버전은 커머스 핵심 Saga와 운영 복구 흐름을 설명 가능한 수준으로 끝까지 연결하는 데 집중했다.
