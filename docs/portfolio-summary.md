# Portfolio Summary

StockRush의 도메인, 구조, 검증 범위를 한 문서로 요약한다. 상세 구현 기준은 `README.md`, `docs/architecture/phase-1-commerce-foundation.md`, `docs/test-strategy.md`, `docs/ai-development-process.md`를 기준으로 한다.

## 한 줄 요약

StockRush는 한정 판매 커머스 주문 흐름을 MSA, Apache Kafka, Outbox, Saga로 구현하고, 고객 앱과 관리자 앱에서 성공/실패/지연 결제 운영 시나리오까지 확인할 수 있게 만든 백엔드 중심 프로젝트다.

## 대표 아키텍처 이미지

![StockRush 아키텍처](assets/architecture/stockrush-architecture.png)

원본 SVG와 발표 자료 활용 기준은 `docs/portfolio/visual-assets.md`에 둔다. README에는 같은 PNG를 연결해 GitHub 첫 화면에서 구조를 바로 확인할 수 있게 했다.

## 1분 설명

StockRush는 한정 판매 상황에서 주문, 재고, 결제가 분산 서비스로 나뉘었을 때의 상태 수렴 문제를 다룹니다.

Order Service가 Saga Orchestrator 역할을 맡고, Inventory Service와 Payment Service는 Kafka 이벤트와 command를 통해 재고 선점, 결제 승인, 실패 복구, 지연 결제 취소 흐름을 처리합니다. 각 서비스는 Outbox와 processed event 저장으로 발행 안정성과 중복 처리 안전성을 확보했습니다.

React/Vite 고객 웹앱에서는 상품 조회, 주문 생성, 주문 상태 추적을 확인할 수 있고, 관리자 웹앱에서는 상품/재고 관리, Saga 상태 확인, 쿠폰 사용 이력, 출고 요청 이력, Outbox 재시도와 failed requeue, 지연 결제 취소를 다룹니다. Expo React Native 기반 Android/iOS 고객 앱도 Gateway-first로 상품/SKU 재고 조회, 쿠폰 견적, 주문 생성, 상태 추적, Read Model 주문 내역 조회까지 연결했습니다. 개발 운영 기록은 Dev RAG, Project MCP, Spark worker/reviewer, Agent Runner, Architecture Guard로 남겼습니다.

대표 화면은 MUI 기반으로 다시 정리했고, README에는 고객 주문 흐름, 관리자 Dashboard, Coupons, Fulfillment, Outbox, 고객 모바일 폭 캡처를 연결했습니다. 포트폴리오용 데모 시드는 Dashboard 지표, 쿠폰 사용 상태, 출고 요청, failed Outbox 샘플을 반복 실행 가능한 데이터로 채웁니다. 캡처 절차는 `docs/runbooks/web-visual-smoke.md`에 정리해 데모 백엔드와 Keycloak 로그인 상태에서 다시 만들 수 있게 했습니다.

## 3분 설명

StockRush의 핵심 문제는 “한정 판매 주문에서 재고와 결제를 한 트랜잭션으로 묶을 수 없을 때 어떻게 일관성 있는 사용자 경험과 운영 복구 경로를 만들 것인가”입니다.

서비스는 gateway, catalog-service, inventory-service, order-service, payment-service, promotion-service, fulfillment-service, read-model-service로 나눴습니다. 초기 로컬 환경은 단일 PostgreSQL 인스턴스 안에 서비스별 schema를 분리했고, 서비스 간 흐름은 Apache Kafka topic으로 연결했습니다. Order Service는 주문 상태와 Saga 상태를 관리하고, Inventory Service는 주문 이벤트를 받아 SKU별 재고를 선점하거나 실패 이벤트를 발행합니다. Payment Service는 `CARD`, `FAIL_CARD`, `DELAY_CARD` 결제수단으로 승인, 실패, 지연 상황을 시뮬레이션합니다. Promotion Service는 쿠폰 정의와 주문 전 할인 견적 API를 제공하고, 주문 이벤트를 소비해 쿠폰 사용 상태를 `RESERVED`, `CONSUMED`, `RELEASED`로 관리합니다. Fulfillment Service는 주문 완료 이후 출고 준비 요청을 기록하고, Read Model Service는 주문 lifecycle event를 고객/관리자용 주문 요약 projection으로 반영합니다.

신뢰성 측면에서는 모든 Kafka 발행을 서비스 로컬 Outbox에서 시작하게 했습니다. 이벤트 발행 실패는 retry/failed 상태로 남기고, 관리 화면에서 조회, 수동 재시도, failed 이벤트 재처리 준비를 할 수 있습니다. Consumer는 processed event를 저장해 같은 메시지가 다시 들어와도 중복 처리를 피하도록 했습니다. 재고 선점은 SKU별 요청 수량을 합산한 뒤 PostgreSQL row lock과 조건부 차감으로 과다 선점을 막는 방향으로 구현했습니다.

운영 화면도 같은 흐름에 맞춰 구성했습니다. 현재 고객 웹앱은 상품 목록, 상품 선택, SKU 재고 확인, 주문 생성, 주문 상태 polling을 제공하고, 관리자 웹앱은 Read Model 조건 검색 대시보드, 상품 등록/수정, SKU 재고 설정, 주문/Saga 추적, 쿠폰 사용 이력, 출고 요청 이력, Outbox 조회/재시도/requeue, 지연 결제 취소를 제공합니다. 모바일 고객 앱은 Expo React Native와 Gateway-first API client로 상품/SKU 재고 조회, 쿠폰 견적, 주문 생성, 상태 추적, Read Model 주문 내역 조회를 연결했습니다. 특히 `DELAY_CARD` 주문은 관리자가 취소 요청을 보내면 `PaymentCancelRequested` command와 `PaymentCanceled` 이벤트를 거쳐 주문 취소와 재고 복구로 이어지게 했습니다.


## 10분 설명 흐름

### 1. 문제 정의

한정 판매 커머스에서는 짧은 시간에 주문 요청이 몰리고, 주문 생성, 재고 선점, 결제 승인 중 어느 단계든 실패할 수 있습니다. 단일 서버에서는 DB 트랜잭션 하나로 묶기 쉽지만, 서비스가 분리되면 재고와 결제를 즉시 함께 확정할 수 없습니다.

이 프로젝트는 이 상황에서 다음 질문에 답하는 것을 목표로 했습니다.

- 주문은 언제 생성 상태로 노출할 것인가
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
| promotion-service | 쿠폰 등록/목록, 주문 전 할인 견적 계산, 주문 이벤트 기반 사용 상태 기록 |
| fulfillment-service | 주문 완료 이후 출고 준비 요청 기록과 관리자 출고 상태 조회 |
| read-model-service | 주문 이벤트 기반 고객/관리자 주문 요약 projection |

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
9. Fulfillment Service가 `OrderConfirmed`를 받아 출고 준비 요청을 `PREPARING`으로 기록한다.
10. Read Model Service가 주문 생성/완료/취소 이벤트를 받아 고객/관리자 조회용 주문 요약을 갱신한다.

실패 흐름에서는 `InventoryReservationFailed` 또는 `PaymentAuthorizationFailed`가 들어오면 Order Service가 주문을 `CANCELLED`, Saga를 `FAILED`로 전환하고 `OrderCancelled`를 발행합니다. Inventory Service는 `OrderCancelled`를 받아 예약 재고를 복구합니다.

지연 흐름에서는 `DELAY_CARD` 결제가 `PaymentAuthorizationDelayed`로 남고, 관리자 취소 요청이 들어오면 `PaymentCancelRequested` command와 `PaymentCanceled` event를 거쳐 같은 취소/복구 흐름으로 합류합니다.

### 4. 신뢰성 설계

Outbox는 Kafka 발행을 DB 저장과 분리해 장애 상황을 운영자가 복구할 수 있게 하는 장치입니다. 주문, 재고, 결제 서비스는 각각 outbox table을 가지고, relay는 PENDING 이벤트를 claim한 뒤 발행 성공 시 PUBLISHED로, 실패 시 retry 또는 FAILED로 전환합니다.

Consumer 멱등성은 같은 이벤트가 여러 번 들어와도 결과가 흔들리지 않게 하는 장치입니다. 각 consumer는 처리한 event id를 processed event로 저장하고, 이미 처리한 이벤트면 부작용을 다시 만들지 않습니다.

재고 선점에서는 같은 SKU가 한 주문 안에 여러 줄로 들어오거나 동시 주문이 들어올 수 있습니다. 이를 위해 요청 수량을 SKU별로 합산하고, DB row lock과 조건부 차감으로 `availableQuantity`가 음수가 되지 않도록 했습니다.

### 5. 운영 화면

고객 앱은 주문 흐름을 확인하는 진입점입니다. 상품을 선택하고 SKU 재고와 쿠폰 할인 견적을 확인한 뒤 `CARD`, `FAIL_CARD`, `DELAY_CARD` 결제수단으로 주문을 생성할 수 있습니다. 주문 생성 후에는 주문 조회 API를 polling해 상태 변화를 보여줍니다.

관리자 앱은 운영 복구 경로를 제공합니다. 상품 등록/수정, SKU 재고 설정, 주문 목록과 Saga 상세 조회, 쿠폰 사용 이력, 출고 요청 이력, 서비스별 Outbox 조회/재시도/requeue, 지연 결제 취소 요청을 한 화면 흐름으로 연결했습니다.

### 6. 검증 전략

검증은 계층별로 나눴습니다.

- Backend: 서비스별 Maven 테스트로 API, Saga handler, Outbox relay, Kafka smoke를 검증
- Frontend: 고객/관리자 앱 Vitest와 production build로 API 호출 모양과 상태 렌더링 검증
- Mobile: Expo React Native 화면 테스트, TypeScript typecheck, scaffold 검증, smoke evidence, Android Expo Go Keycloak 보호 주문 완료 UI smoke를 확인
- Gateway: 주문 생성/조회, 관리자 주문 조회/취소, Outbox 조회/재시도/requeue 라우팅 smoke로 path, query, header, body, status 전달을 검증
- Promotion/Order pricing: 쿠폰 생성/목록/견적/사용 이력, 주문 할인 반영, Payment command 결제 예정 금액, 쿠폰 사용 상태 전이를 검증
- Fulfillment: 주문 완료 이벤트 소비, 출고 준비 요청 생성, 중복 이벤트 처리, 관리자 출고 요청 조회를 검증
- Read Model: 주문 생성/완료/취소 이벤트 projection, JSON consumer dispatch, 고객/관리자 주문 요약 API와 조건 검색을 검증
- Architecture Guard: schema ownership, Controller 반환 타입, event envelope, outbox table shape 점검
- Local E2E: `CARD`, `FAIL_CARD`, `DELAY_CARD`, 지연 결제 취소, Gateway 경유 동일 SKU 최종 상태를 실제 로컬 서비스와 Kafka로 확인

문서에는 구현된 검증과 남은 공백을 분리했습니다. 현재 동일 SKU runner와 runbook의 주문 생성/조회/취소 및 Outbox 운영 경로는 Gateway를 경유합니다. Outbox retry/requeue는 서비스별 감사 로그를 남깁니다. Promotion은 고객 앱 견적 UI, 주문 할인 반영, 주문 이벤트 기반 쿠폰 사용 상태, 관리자 사용 이력 화면까지 연결했고, Fulfillment는 주문 완료 이벤트 기반 출고 준비 요청, Gateway route, 관리자 출고 요청 화면까지 연결했습니다. Read Model은 주문 요약 projection, Gateway 경유 고객/관리자 API, 주문 ID/회원 ID/상태/Saga 상태/쿠폰 코드 조건 검색이 가능한 관리자 대시보드까지 연결했습니다. 고객 상품 검색은 Catalog API와 Customer App UI로 먼저 연결했고, Kafka 장애 복구는 demo compose pause/unpause 기반 opt-in smoke로 자동화했습니다. 보안은 공개 route를 `GET /api/products`, `GET /api/stocks`, `POST /api/coupons/quote`로 제한하고, 고객 주문/상세/주문 내역은 `ROLE_CUSTOMER` + subject forwarding, 관리자 라우트는 `ROLE_ADMIN` + 인증 주체 전달로 통일했습니다. service-local direct 경로는 내부/dev 전용으로 분리해 공개 경로에서 제외했습니다. live IdP browser smoke evidence와 Android Expo Go 보호 주문 완료 UI smoke evidence는 확보했습니다. iOS는 full Xcode `simctl` 미설치로 현재 로컬 실기동 증거가 제한됩니다.

### 7. Agent 기반 개발 운영

이 프로젝트에서 AI 활용은 산출물 자체보다 운영 방식이 중요합니다.

- Dev RAG로 ADR, API 문서, 실행 기록을 검색 가능한 문맥으로 관리
- Project MCP로 문서 조회와 검증 명령 조회를 표준화
- Spark worker/reviewer를 분리해 구현과 리뷰 책임을 나눔
- Architecture Guard로 사람이 놓치기 쉬운 서비스 경계와 이벤트 규칙을 자동 점검

핵심은 “AI로 빠르게 만들었다”가 아니라 “AI가 만든 변경을 어떤 문맥과 검증 체계 안에서 통제했는지”다.

### 8. 의도적으로 남긴 확장 지점

현재 범위는 주문, 재고, 결제, 운영 복구 흐름 검증에 맞춰져 있다. 핵심 흐름은 끝까지 연결하되, 아래 항목은 후속 확장으로 남겼다.

- Fulfillment Service: carrier/label/tracking 상태
- Read Model: 상품 검색 projection
- Observability: OpenTelemetry, Prometheus, Grafana
- Mobile App: iOS simulator 또는 development build 기반 추가 교차 검증
- Resilience Test: consumer lag, 부하 벤치마크와 consumer 병렬성 검증

## 요약 Bullet 후보

- Apache Kafka, Outbox, Saga 기반 한정 판매 주문 흐름을 MSA 구조로 구현하고, 결제 실패와 지연 결제 취소의 주문/재고 복구 흐름을 로컬 E2E로 검증
- Order Service를 Saga Orchestrator로 설계해 `InventoryReserved`, `PaymentAuthorized`, `PaymentAuthorizationFailed`, `PaymentCanceled` 이벤트에 따른 주문 상태 전이를 구현
- 서비스별 Outbox relay와 processed event 저장을 적용해 Kafka 발행 실패 재시도와 consumer 중복 처리 안전성을 확보
- 고객 앱과 관리자 앱을 함께 구현해 상품 조회, 주문 생성, 주문 상태 추적, 상품/재고 관리, Outbox retry/requeue, 지연 결제 취소 운영 흐름을 확인 가능하게 구성
- MUI 기반 웹 화면 정리와 브라우저 캡처를 추가해 고객 주문 흐름, 관리자 Dashboard, Outbox 운영 화면을 README에서 바로 확인 가능하게 구성
- Expo React Native 기반 고객 앱에 Gateway-first 상품/SKU 재고 조회, 쿠폰 견적, 주문 생성, 상태 추적, Read Model 주문 내역을 연결하고 Jest/typecheck/preflight 증거를 남김
- Promotion quote를 고객 앱과 주문 생성에 연결해 쿠폰 할인액과 결제 예정 금액을 주문 snapshot 및 결제 command에 반영
- Promotion Service가 `OrderCreated`, `OrderConfirmed`, `OrderCancelled`를 소비해 쿠폰 사용 상태를 기록하고 주문 실패 시 사용 해제로 복구하도록 구현
- Fulfillment Service가 `OrderConfirmed`를 소비해 출고 준비 요청을 기록하고, Gateway와 관리자 앱에서 출고 요청 이력을 조회하도록 구현
- Read Model Service가 주문 lifecycle event를 소비해 고객 주문 내역과 관리자 주문 요약 projection을 갱신하고 조건 검색 가능한 대시보드로 연결하도록 구현
- Dev RAG, Project MCP, Spark worker/reviewer, Agent Runner, Architecture Guard를 결합해 agent 기반 개발 운영과 검증 증적을 프로젝트 산출물로 정리
- Maven, Vitest, production build, Architecture Guard, Gateway 경유 동일 SKU runner, 로컬 E2E runbook으로 테스트 계층과 검증 절차를 문서화

## 설명 포인트

### 왜 MSA를 선택했나

단순 CRUD 구조에서는 서비스 분리 이후 생기는 일관성, 장애 복구, 운영 가시성 문제를 다루기 어렵다. 한정 판매 도메인은 주문, 재고, 결제 경계가 자연스럽고 Kafka/Saga/Outbox를 적용할 이유가 분명하기 때문에 MSA로 구성했다.

### 왜 Kafka를 선택했나

실무에서 널리 쓰이는 도구이고, 주문 이벤트와 결제 command처럼 비동기 흐름을 다루기 좋다. Kafka 호환 도구보다 Apache Kafka 자체를 사용해 broker, topic, consumer, relay 동작을 직접 확인하는 쪽을 선택했다.

### Outbox를 왜 넣었나

주문 저장과 Kafka 발행을 하나의 외부 트랜잭션으로 묶기 어렵기 때문이다. DB에는 주문과 outbox event를 같이 저장하고, relay가 나중에 Kafka로 발행하게 하면 발행 실패가 운영 가능한 상태로 남는다.

### Saga Orchestrator 방식의 장점은 무엇인가

주문 상태의 최종 책임을 Order Service에 둘 수 있다. 재고와 결제 서비스는 자기 책임 범위의 결과 이벤트를 발행하고, Order Service가 그 결과를 모아 주문 상태와 다음 command를 결정한다.

### 중복 메시지는 어떻게 처리했나

Consumer가 처리한 event id를 processed event로 저장한다. 이미 처리한 event id가 다시 들어오면 재고 차감, 결제 생성, outbox 기록 같은 부작용을 반복하지 않는다.

### 재고 과다 선점은 어떻게 막았나

주문 요청의 SKU별 수량을 먼저 합산하고, 재고 row를 잠근 상태에서 조건부로 available 수량을 차감한다. 이 방식은 동일 SKU 동시 주문 회귀 테스트와 로컬 최종 상태 E2E runner로 검증해, 한정된 재고보다 많이 예약되지 않도록 했다.

### AI 활용에서 차별점은 무엇인가

Agent 도구는 단순 코드 생성보다 변경 추적과 검증 가시성에 맞춰 사용했다. Dev RAG와 MCP로 문맥을 고정하고, Spark worker/reviewer로 역할을 분리하고, Agent Runner와 Architecture Guard로 실행 증거와 정적 검증을 남겼다.

### 지금 가장 큰 한계는 무엇인가

Gateway 관리자/고객 인증·권한은 OAuth2 Resource Server 기반으로 시작했고, demo Keycloak realm과 smoke token 취득 흐름까지 연결했다. 고객 주문 생성/상세/주문 내역은 인증 subject 기준으로 동작하며, 다른 고객 주문 조회와 재시도 키 replay는 차단한다. Customer/Admin Web과 Mobile App은 OIDC PKCE 로그인 상태에서 protected API에 Bearer token을 전달한다. 관리자 운영 감사는 Gateway가 인증 주체 기반 operator를 전달하고, Outbox 운영/지연 결제 취소/상품 변경/재고 변경이 서비스별 감사 row를 남긴다. public route는 Gateway 공개 진입점으로 `GET /api/products`, `GET /api/stocks`, `POST /api/coupons/quote`만 유지하고 레거시 직접 호출 경로는 내부/dev 용도로만 분리돼 있다. live IdP browser smoke evidence와 Android Expo Go 보호 주문 완료 증거는 확보했다. iOS는 full Xcode `simctl` 미설치로 현재 로컬 실기동 증거가 제한된다.
