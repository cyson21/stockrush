# StockRush

StockRush는 한정 판매 주문 흐름에서 Kafka, Outbox, Saga를 묶은 엔드투엔드 동작 중심 포트폴리오 프로젝트입니다.

## 현재 구현/검증 상태(요약)

이 단계에서 아래 구간은 구현되어 있으며, `CARD`/`FAIL_CARD`/`DELAY_CARD`와 지연 결제 취소는 실제 로컬 서비스와 Kafka로 E2E 확인했습니다.

- 고객 앱(`apps/customer-app`) + 주문 생성/조회 플로우
- 관리자 앱(`apps/admin-app`) + 운영 화면(상품, 재고, 주문, Saga, Outbox)
- Catalog / Inventory / Order / Payment API 체인
- Promotion Service 쿠폰 등록/목록/할인 견적 API와 주문 이벤트 기반 사용 상태 기록
- Fulfillment Service 주문 완료 이벤트 기반 출고 준비 요청 기록과 관리자 출고 상태 조회
- Read Model Service 주문 이벤트 기반 고객/관리자 주문 요약 projection
- 고객 앱 쿠폰 견적 UI와 Order Service 주문 할인 반영
- Expo React Native 기반 Android/iOS 고객 모바일 앱 scaffold, Gateway-first API client, 상품/SKU 재고 조회, 쿠폰 견적, 주문 생성, 주문 상태 추적, 주문 내역 화면
- Kafka + 서비스 로컬 Outbox + Saga 상태 전이
- `CARD` 성공, `FAIL_CARD` 실패/재고 복구, `DELAY_CARD` 지연 결제와 관리자 취소 흐름

## 실행 문서

- [로컬 E2E 실행 가이드](docs/runbooks/local-e2e.md)
- [로컬 인프라 실행 가이드](infra/local/README.md)
- [이식형 데모 런타임 실행 가이드](infra/demo/README.md)
- [고객 앱 가이드](apps/customer-app/README.md)
- [관리자 앱 가이드](apps/admin-app/README.md)
- [모바일 앱 가이드](apps/mobile-app/README.md)
- [서비스 실행 가이드](services/README.md)
- [CI/CD 운영 기준](docs/ci-cd.md)

## 빠른 실행 순서

현재 실행 방식은 개발 모드와 데모 모드로 분리한다.

개발 모드는 빠른 디버깅을 위해 Docker Compose로 PostgreSQL, Redis, Kafka, Kafka UI만 실행하고, Spring Boot 서비스와 앱은 host 런타임에서 실행한다.

1. `infra/local`에서 PostgreSQL, Redis, Kafka, Kafka UI를 실행합니다.
2. gateway, catalog-service, inventory-service, order-service, payment-service를 각각 기동합니다.
   - 쿠폰 API를 확인할 때는 promotion-service도 함께 기동합니다.
   - 출고 준비 요청을 확인할 때는 fulfillment-service도 함께 기동합니다.
   - 주문 요약 projection을 확인할 때는 read-model-service도 함께 기동합니다.
3. 현재 구현된 React/Vite customer-app과 admin-app을 실행합니다.
4. 모바일 앱은 `apps/mobile-app`에서 Expo scaffold, API client, 상품/SKU 재고, 쿠폰/주문 생성, 주문 상태 추적, 주문 내역 화면을 확인합니다.
5. [Local E2E Runbook](docs/runbooks/local-e2e.md)에 따라 `CARD`, `FAIL_CARD`, `DELAY_CARD`, 지연 결제 취소 시나리오를 확인합니다.

자세한 명령은 실행 문서에 분리했습니다. README는 포트폴리오 설명과 검증 기준을 빠르게 파악하는 진입점으로 유지합니다.

```bash
cd infra/local
docker compose up -d
```

서비스와 앱 기동 명령은 [Local E2E Runbook](docs/runbooks/local-e2e.md)과 [서비스 실행 가이드](services/README.md)를 기준으로 실행합니다.

데모 모드는 `infra/demo` Docker Compose로 인프라, 백엔드 서비스, 웹앱을 함께 실행해 macOS와 Windows 11에서 재현 가능하게 구성합니다.

```bash
./scripts/demo-up.sh
./scripts/demo-smoke.sh
./scripts/demo-down.sh
```

Windows 11 PowerShell에서는 `.\scripts\demo-up.ps1`, `.\scripts\demo-smoke.ps1`, `.\scripts\demo-down.ps1`을 사용합니다. 자세한 내용은 [Demo Runtime Guide](infra/demo/README.md)에 둡니다.

## 핵심 공개 문서

- [Phase 1 Commerce Foundation](docs/architecture/phase-1-commerce-foundation.md)
- [Customer App Flow](docs/architecture/customer-app-flow.md)
- [Admin App Flow](docs/architecture/admin-app-flow.md)
- [Mobile App Flow](docs/architecture/mobile-app-flow.md)
- [Product Discovery](docs/architecture/product-discovery.md)
- [Event Envelope](docs/architecture/events.md)
- [Outbox and Consumer Idempotency](docs/architecture/outbox.md)
- [Observability Baseline](docs/architecture/observability.md)
- [Test Strategy](docs/test-strategy.md)
- [Portfolio Summary](docs/portfolio-summary.md)
- [Troubleshooting](docs/troubleshooting/phase-1-commerce-foundation.md)
- [Catalog and Inventory API](docs/api/catalog-inventory.md)
- [Customer Order API](docs/api/customer-orders.md)
- [Catalog Admin API](docs/api/catalog-admin.md)
- [Promotion API](docs/api/promotion.md)
- [Fulfillment API](docs/api/fulfillment.md)
- [Read Model API](docs/api/read-model.md)
- [Admin Order API](docs/api/admin-orders.md)
- [Outbox Admin API](docs/api/outbox-admin.md)
- [Kafka 기반 MSA 선택 ADR](docs/adr/0001-kafka-based-msa.md)
- [Development Operations Architecture](docs/architecture/development-operations.md)
- [AI Development Process](docs/ai-development-process.md)

## 아키텍처 요약

| 영역 | 선택 |
|---|---|
| 서비스 구조 | gateway, catalog-service, inventory-service, order-service, payment-service, promotion-service, fulfillment-service, read-model-service |
| 비동기 메시징 | Apache Kafka topic 기반 event/command 흐름 |
| 일관성 처리 | Order Service 중심 Saga Orchestration |
| 발행 안정성 | 서비스별 Outbox relay와 retry/failed 상태 |
| 중복 처리 | Consumer processed event 저장 |
| 데이터 저장 | 단일 PostgreSQL 인스턴스 안의 서비스별 schema |
| 앱 | React/Vite 고객/관리자 웹앱, Expo React Native 고객 모바일 앱 |
| AI 개발 운영 | Dev RAG, Project MCP, Spark worker/reviewer, Agent Runner, Architecture Guard |
| CI/CD | GitHub Actions, GHCR, Docker Compose local deploy |

## 기술 선택 이유

- Apache Kafka: 주문, 재고, 결제 사이의 비동기 흐름과 재처리 가능성을 보여주기 위해 사용했습니다.
- Outbox: DB 상태 변경과 Kafka 발행 사이의 실패 지점을 운영자가 확인하고 재시도할 수 있게 만들기 위해 사용했습니다.
- Saga Orchestration: 주문 상태의 최종 책임을 Order Service에 두고, 재고/결제 결과 이벤트에 따라 다음 흐름을 결정하기 위해 선택했습니다.
- PostgreSQL schema 분리: 초기 개발 속도를 유지하면서도 서비스별 데이터 소유권을 명확히 하기 위해 사용했습니다.
- React/Vite 웹앱: 백엔드 흐름을 고객/관리자 관점에서 빠르게 시연할 수 있게 하기 위해 최소 웹앱을 먼저 구현했습니다.
- Expo React Native 모바일 앱: Android/iOS에서 고객 주문 흐름과 Read Model 기반 주문 내역을 시연할 수 있도록 Gateway-first client 구조를 잡고, 상품/SKU 재고 조회, 쿠폰/주문 생성, 주문 상태 추적, 주문 내역 화면을 연결했습니다.
- GitHub Actions/GHCR: 개인 AWS 자원을 쓰지 않고도 테스트, 이미지 발행, 로컬 배포를 자동화하기 위해 사용했습니다.

## 실행 상태에서 확인되는 핵심 포인트

- 주문 서비스(`order-service`)가 Saga Orchestrator 역할을 하며, Outbox를 통해 Kafka 이벤트를 발행합니다.
- 재고 서비스는 `InventoryReserved / InventoryReservationFailed / InventoryReservationConfirmed / InventoryReservationReleased`로 결제와 연동해 예약 수량을 보정합니다.
- 결제 서비스는 `CARD` 승인, `FAIL_CARD` 실패, `DELAY_CARD` 지연, 지연 결제 취소 분기 검증이 가능하며, 주문 상태와 Saga 상태 변화가 연동되어 보입니다.
- 관리자 앱에서 상품 등록/수정, SKU 재고 설정, 지연 결제 취소, 주문 Saga 추적, Outbox retry와 failed requeue를 한 흐름으로 확인할 수 있습니다.
- Outbox 운영 액션은 `X-Operator-Id`, `X-Correlation-Id`, batch size, 처리 건수를 서비스별 감사 테이블에 남깁니다.
- Promotion Service는 쿠폰 등록/목록과 주문 전 할인 견적 계산을 제공하고, 주문 이벤트를 소비해 쿠폰 사용 상태를 기록합니다. Order Service는 주문 생성 시 할인 가격 snapshot을 저장해 결제 예정 금액을 Payment command로 전달합니다.
- Fulfillment Service는 `OrderConfirmed`를 소비해 주문별 출고 준비 요청을 `PREPARING` 상태로 기록하고, 관리자 출고 상태 조회 API를 제공합니다.
- Read Model Service는 주문 생성/완료/취소 이벤트를 소비해 `read_model.order_summaries`를 갱신하고 고객 주문 내역과 관리자 주문 요약 API를 제공합니다. 관리자 대시보드는 주문 ID, 회원 ID, 주문 상태, Saga 상태, 쿠폰 코드 조건 검색을 지원합니다.

## 대표 시나리오

| 시나리오 | 기대 결과 |
|---|---|
| `CARD` 주문 | 주문 `CONFIRMED`, Saga `COMPLETED`, 예약 재고 확정 |
| `FAIL_CARD` 주문 | 주문 `CANCELLED`, Saga `FAILED`, 예약 재고 복구 |
| `DELAY_CARD` 주문 | 주문 `CREATED`, Saga `PAYMENT_DELAYED`, 관리자 취소 가능 |
| 지연 결제 취소 | `PaymentCancelRequested`와 `PaymentCanceled` 이후 주문 취소 및 재고 복구 |
| Outbox 운영 | 서비스별 outbox 조회, due `PENDING` 이벤트 retry, `FAILED` 이벤트 requeue |
| 쿠폰 견적/주문 할인 | 쿠폰 코드와 주문 금액으로 할인액을 산출하고, 주문 생성 후 결제 예정 금액으로 결제 요청 |
| 쿠폰 사용 복구 | `OrderCreated` 쿠폰 사용 기록 후 `OrderConfirmed`는 사용 완료, `OrderCancelled`는 사용 해제 |
| 출고 준비 요청 | `OrderConfirmed` 이후 Fulfillment Service가 주문별 출고 준비 요청 기록 |
| 주문 요약 projection | `OrderCreated` 이후 요약 생성, `OrderConfirmed`/`OrderCancelled` 이후 상태 갱신 |

## 검증 요약

- 백엔드는 서비스별 `mvn test`로 API, Outbox relay, Kafka smoke, Saga handler를 검증합니다.
- 고객/관리자 앱은 Vitest와 production build로 API 호출 모양, 상태 렌더링, 재시도 키 재사용을 검증합니다.
- 모바일 앱은 상품/SKU 재고, 쿠폰 견적, 주문 생성, 주문 상태 추적, Read Model 주문 내역 화면을 React Native Testing Library, Jest Expo, TypeScript typecheck, scaffold validation으로 검증했습니다. `smoke:preflight`로 demo Gateway health와 iOS/Android 실행 도구 준비 상태를 점검하며, Android 또는 iOS live smoke는 의존성 설치와 simulator/emulator 준비 후 진행합니다.
- `./tools/architecture-guard/architecture-guard check`로 schema ownership, Controller 반환 타입, 이벤트 envelope, Outbox table shape, Gateway/서비스 Correlation ID 전파, Actuator 운영 endpoint 노출을 점검합니다.
- 실제 로컬 E2E는 [Local E2E Runbook](docs/runbooks/local-e2e.md)의 `CARD`, `FAIL_CARD`, `DELAY_CARD`, 지연 결제 취소 시나리오를 기준으로 재현합니다.
- Kafka broker 장애 복구는 `./tools/local-e2e/local-e2e kafka-outage-recovery` 또는 `./scripts/demo-smoke.sh --kafka-outage`로 선택 실행합니다.
- 최근 지연 결제 취소 E2E 증거: `ord_20260513012031_8c06cd49` 주문이 `CREATED/PAYMENT_DELAYED` 도달 후 관리자 취소로 `CANCELLED/FAILED`가 됐고, SKU `DELAY-E2E-102029-S` 재고는 `available=20`, `reserved=0`으로 복구됐습니다.
- 동일 SKU 최종 상태 E2E 증거: `tools/local-e2e/local-e2e same-sku-concurrency` 실행에서 주문 생성/조회는 Gateway를 경유했고, 주문 6건, 초기 재고 3개 기준 3건 완료/3건 취소, 재고 `available=0`, `reserved=0`, 서비스별 `pendingOutboxDelta=0`을 확인했습니다.
- Gateway 주문/운영 라우팅 smoke 증거: fake upstream 기준 주문 생성/조회, 관리자 주문 조회/취소, Outbox 조회/재시도/requeue, 쿠폰 견적/사용 이력, Read Model 주문 요약이 method, path, query, body, 핵심 헤더, status, body를 전달하는지 `services/gateway` Maven 테스트로 확인했습니다.
- Gateway 주문 시나리오 E2E 증거: `GW-E2E-20260513111940-332ba0dc` 기준 `CARD`, `FAIL_CARD`, `DELAY_CARD`, 지연 결제 취소가 Gateway 주문 경로에서 처리됐고, 최종 재고 `available=19`, `reserved=0`, 서비스별 `pendingOutboxDelta=0`을 확인했습니다.
- Kafka 장애 복구 smoke 증거: `KAFKA-OUTAGE-E2E-20260515014056-84904d76` 기준 Kafka pause 중 주문 `ord_20260514164100_80a5f7f8`가 `CREATED/STARTED`와 order outbox 1건으로 머문 뒤, unpause 후 `CONFIRMED/COMPLETED`, 재고 `available=2`, `reserved=0`, 잔여 outbox 0으로 수렴했습니다.
- Promotion Service 집중 검증: `PromotionCouponControllerIntegrationTest`로 쿠폰 생성, 상태별 목록, 퍼센트 할인 상한, 최소 주문 금액 미달, 중복 쿠폰 코드 응답을 확인했습니다.
- 쿠폰 주문 반영 검증: Order Service 테스트로 quote 실패/타임아웃/금액 일관성, 주문 저장 가격 snapshot, Payment command 결제 예정 금액을 확인하고 Customer App Vitest/build로 쿠폰 UI를 확인했습니다.
- 쿠폰 사용 이벤트 검증: Promotion Service 테스트로 `OrderCreated` 사용 기록, `OrderConfirmed` 사용 완료, `OrderCancelled` 사용 해제와 중복 이벤트 무해 처리를 확인했습니다.
- 출고 준비 이벤트 검증: Fulfillment Service 테스트로 `OrderConfirmed` 출고 준비 요청 생성과 중복 이벤트 무해 처리를 확인했습니다.
- 주문 요약 projection 검증: Read Model Service 테스트로 주문 생성/완료/취소 이벤트 처리, JSON consumer dispatch, 고객/관리자 조회 API와 조건 검색을 확인했습니다.

## 현재 한계

- Gateway는 주문 생성/조회, 관리자 주문 조회/취소, Outbox 조회/재시도/requeue 라우팅 smoke와 동일 SKU runner/runbook의 Gateway 경유 경로까지 검증 범위를 넓혔습니다.
- Promotion Service는 주문 이벤트 기반 쿠폰 사용 상태, Gateway 쿠폰 견적/사용 이력 route, 관리자 사용 이력 화면까지 연결했습니다.
- Fulfillment Service는 출고 준비 요청 기록, Gateway route, 관리자 출고 상태 화면까지 연결했습니다. carrier/label/tracking 상태는 후속 확장 범위입니다.
- Read Model Service는 주문 요약 projection, 서비스-local 조회 API, Gateway 조회 route, 조건 검색 가능한 관리자 대시보드까지 연결했습니다. 고객 상품 검색은 Catalog API와 Customer App UI로 먼저 연결했고, 별도 상품 검색 projection은 후속 확장 범위입니다.
- 인증/권한, 부하 벤치마크, Kafka consumer 병렬성 검증은 후속 확장 범위입니다.
