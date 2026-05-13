# StockRush

StockRush는 한정 판매 주문 흐름에서 Kafka, Outbox, Saga를 묶은 엔드투엔드 동작 중심 포트폴리오 프로젝트입니다.

## 현재 구현 상태(요약)

이 단계에서 아래 구간은 실제로 연결되어 검증했습니다.

- 고객 앱(`apps/customer-app`) + 주문 생성/조회 플로우
- 관리자 앱(`apps/admin-app`) + 운영 화면(상품, 재고, 주문, Saga, Outbox)
- Catalog / Inventory / Order / Payment API 체인
- Kafka + 서비스 로컬 Outbox + Saga 상태 전이
- `CARD` 성공, `FAIL_CARD` 실패/재고 복구, `DELAY_CARD` 지연 결제 흐름

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
- [Catalog Admin API](docs/api/catalog-admin.md)
- [Admin Order API](docs/api/admin-orders.md)
- [Outbox Admin API](docs/api/outbox-admin.md)
- [Kafka 기반 MSA 선택 ADR](docs/adr/0001-kafka-based-msa.md)
- [Development Operations Architecture](docs/architecture/development-operations.md)
- [AI Development Process](docs/ai-development-process.md)

## 실행 상태에서 확인되는 핵심 포인트

- 주문 서비스(`order-service`)가 Saga Orchestrator 역할을 하며, Outbox를 통해 Kafka 이벤트를 발행합니다.
- 재고 서비스는 `InventoryReserved / InventoryReservationFailed / InventoryReservationConfirmed / InventoryReservationReleased`로 결제와 연동해 예약 수량을 보정합니다.
- 결제 서비스는 `CARD` 승인, `FAIL_CARD` 실패, `DELAY_CARD` 지연 분기 검증이 가능하며, 주문 상태와 Saga 상태 변화가 연동되어 보입니다.
- 관리자 앱에서 상품 등록/수정, SKU 재고 설정, 주문 Saga 추적, Outbox retry를 한 흐름으로 확인할 수 있습니다.
