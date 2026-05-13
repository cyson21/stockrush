# StockRush

StockRush는 한정 판매 주문 흐름에서 Kafka, Outbox, Saga를 묶은 엔드투엔드 동작 중심 포트폴리오 프로젝트입니다.

## 현재 구현 상태(요약)

이 단계에서 아래 구간은 실제로 연결되어 검증했습니다.

- 고객 앱(`apps/customer-app`) + 주문 생성/조회 플로우
- 관리자 앱(`apps/admin-app`) + 운영 화면(상품, 재고, 주문, Saga, Outbox)
- Catalog / Inventory / Order / Payment API 체인
- Kafka + 서비스 로컬 Outbox + Saga 상태 전이
- `CARD` 성공, `FAIL_CARD` 실패/재고 복구, `DELAY_CARD` 지연 결제와 관리자 취소 흐름

## 실행 문서

- [로컬 E2E 실행 가이드](docs/runbooks/local-e2e.md)
- [로컬 인프라 실행 가이드](infra/local/README.md)
- [고객 앱 가이드](apps/customer-app/README.md)
- [관리자 앱 가이드](apps/admin-app/README.md)
- [서비스 실행 가이드](services/README.md)

## 핵심 공개 문서

- [Phase 1 Commerce Foundation](docs/architecture/phase-1-commerce-foundation.md)
- [Customer App Flow](docs/architecture/customer-app-flow.md)
- [Admin App Flow](docs/architecture/admin-app-flow.md)
- [Event Envelope](docs/architecture/events.md)
- [Outbox and Consumer Idempotency](docs/architecture/outbox.md)
- [Test Strategy](docs/test-strategy.md)
- [Catalog and Inventory API](docs/api/catalog-inventory.md)
- [Customer Order API](docs/api/customer-orders.md)
- [Catalog Admin API](docs/api/catalog-admin.md)
- [Admin Order API](docs/api/admin-orders.md)
- [Outbox Admin API](docs/api/outbox-admin.md)
- [Kafka 기반 MSA 선택 ADR](docs/adr/0001-kafka-based-msa.md)
- [Development Operations Architecture](docs/architecture/development-operations.md)
- [AI Development Process](docs/ai-development-process.md)

## 실행 상태에서 확인되는 핵심 포인트

- 주문 서비스(`order-service`)가 Saga Orchestrator 역할을 하며, Outbox를 통해 Kafka 이벤트를 발행합니다.
- 재고 서비스는 `InventoryReserved / InventoryReservationFailed / InventoryReservationConfirmed / InventoryReservationReleased`로 결제와 연동해 예약 수량을 보정합니다.
- 결제 서비스는 `CARD` 승인, `FAIL_CARD` 실패, `DELAY_CARD` 지연, 지연 결제 취소 분기 검증이 가능하며, 주문 상태와 Saga 상태 변화가 연동되어 보입니다.
- 관리자 앱에서 상품 등록/수정, SKU 재고 설정, 지연 결제 취소, 주문 Saga 추적, Outbox retry를 한 흐름으로 확인할 수 있습니다.

## 검증 요약

- 백엔드는 서비스별 `mvn test`로 API, Outbox relay, Kafka smoke, Saga handler를 검증합니다.
- 고객/관리자 앱은 Vitest와 production build로 API 호출 모양, 상태 렌더링, 재시도 키 재사용을 검증합니다.
- `./tools/architecture-guard/architecture-guard check`로 schema ownership, Controller 반환 타입, 이벤트 envelope, Outbox table shape를 점검합니다.
- 실제 로컬 E2E는 [Local E2E Runbook](docs/runbooks/local-e2e.md)의 `CARD`, `FAIL_CARD`, `DELAY_CARD`, 지연 결제 취소 시나리오를 기준으로 재현합니다.

## 현재 한계

- Gateway는 헬스체크 중심의 기본 진입점이며, 주요 E2E는 서비스 포트 직접 호출 기준입니다.
- 인증/권한, 동시성 경합 자동 테스트, Kafka 장애 복구 자동화는 후속 확장 범위입니다.
