# StockRush

StockRush는 한정 판매 주문 흐름에서 Kafka, Outbox, Saga를 묶은 엔드투엔드 동작 중심 포트폴리오 프로젝트입니다.

## 현재 구현/검증 상태(요약)

이 단계에서 아래 구간은 구현되어 있으며, `CARD`/`FAIL_CARD`는 실제 로컬 E2E까지 확인했습니다. `DELAY_CARD`와 지연 결제 취소는 자동 테스트와 runbook 기준을 갖췄고, 실제 서비스 기동 E2E는 후속 확인 항목으로 남겼습니다.

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

## 빠른 실행 순서

1. `infra/local`에서 PostgreSQL, Redis, Kafka, Kafka UI를 실행합니다.
2. gateway, catalog-service, inventory-service, order-service, payment-service를 각각 기동합니다.
3. customer-app과 admin-app을 실행합니다.
4. [Local E2E Runbook](docs/runbooks/local-e2e.md)에 따라 `CARD`, `FAIL_CARD`, `DELAY_CARD`, 지연 결제 취소 시나리오를 확인합니다.

자세한 명령은 실행 문서에 분리했습니다. README는 포트폴리오 설명과 검증 기준을 빠르게 파악하는 진입점으로 유지합니다.

```bash
cd infra/local
docker compose up -d
```

서비스와 앱 기동 명령은 [Local E2E Runbook](docs/runbooks/local-e2e.md)과 [서비스 실행 가이드](services/README.md)를 기준으로 실행합니다.

## 핵심 공개 문서

- [Phase 1 Commerce Foundation](docs/architecture/phase-1-commerce-foundation.md)
- [Customer App Flow](docs/architecture/customer-app-flow.md)
- [Admin App Flow](docs/architecture/admin-app-flow.md)
- [Event Envelope](docs/architecture/events.md)
- [Outbox and Consumer Idempotency](docs/architecture/outbox.md)
- [Test Strategy](docs/test-strategy.md)
- [Portfolio Summary](docs/portfolio-summary.md)
- [Troubleshooting](docs/troubleshooting/phase-1-commerce-foundation.md)
- [Catalog and Inventory API](docs/api/catalog-inventory.md)
- [Customer Order API](docs/api/customer-orders.md)
- [Catalog Admin API](docs/api/catalog-admin.md)
- [Admin Order API](docs/api/admin-orders.md)
- [Outbox Admin API](docs/api/outbox-admin.md)
- [Kafka 기반 MSA 선택 ADR](docs/adr/0001-kafka-based-msa.md)
- [Development Operations Architecture](docs/architecture/development-operations.md)
- [AI Development Process](docs/ai-development-process.md)

## 아키텍처 요약

| 영역 | 선택 |
|---|---|
| 서비스 구조 | gateway, catalog-service, inventory-service, order-service, payment-service |
| 비동기 메시징 | Apache Kafka topic 기반 event/command 흐름 |
| 일관성 처리 | Order Service 중심 Saga Orchestration |
| 발행 안정성 | 서비스별 Outbox relay와 retry/failed 상태 |
| 중복 처리 | Consumer processed event 저장 |
| 데이터 저장 | 단일 PostgreSQL 인스턴스 안의 서비스별 schema |
| 앱 | React/Vite 고객 앱과 관리자 앱 |
| AI 개발 운영 | Dev RAG, Project MCP, Spark worker/reviewer, Agent Runner, Architecture Guard |

## 기술 선택 이유

- Apache Kafka: 주문, 재고, 결제 사이의 비동기 흐름과 재처리 가능성을 보여주기 위해 사용했습니다.
- Outbox: DB 상태 변경과 Kafka 발행 사이의 실패 지점을 운영자가 확인하고 재시도할 수 있게 만들기 위해 사용했습니다.
- Saga Orchestration: 주문 상태의 최종 책임을 Order Service에 두고, 재고/결제 결과 이벤트에 따라 다음 흐름을 결정하기 위해 선택했습니다.
- PostgreSQL schema 분리: 초기 개발 속도를 유지하면서도 서비스별 데이터 소유권을 명확히 하기 위해 사용했습니다.
- React/Vite 앱: 백엔드 흐름을 고객/관리자 관점에서 직접 시연할 수 있게 하기 위해 최소 앱을 함께 구현했습니다.

## 실행 상태에서 확인되는 핵심 포인트

- 주문 서비스(`order-service`)가 Saga Orchestrator 역할을 하며, Outbox를 통해 Kafka 이벤트를 발행합니다.
- 재고 서비스는 `InventoryReserved / InventoryReservationFailed / InventoryReservationConfirmed / InventoryReservationReleased`로 결제와 연동해 예약 수량을 보정합니다.
- 결제 서비스는 `CARD` 승인, `FAIL_CARD` 실패, `DELAY_CARD` 지연, 지연 결제 취소 분기 검증이 가능하며, 주문 상태와 Saga 상태 변화가 연동되어 보입니다.
- 관리자 앱에서 상품 등록/수정, SKU 재고 설정, 지연 결제 취소, 주문 Saga 추적, Outbox retry를 한 흐름으로 확인할 수 있습니다.

## 대표 시나리오

| 시나리오 | 기대 결과 |
|---|---|
| `CARD` 주문 | 주문 `CONFIRMED`, Saga `COMPLETED`, 예약 재고 확정 |
| `FAIL_CARD` 주문 | 주문 `CANCELLED`, Saga `FAILED`, 예약 재고 복구 |
| `DELAY_CARD` 주문 | 주문 `CREATED`, Saga `PAYMENT_DELAYED`, 관리자 취소 가능 |
| 지연 결제 취소 | `PaymentCancelRequested`와 `PaymentCanceled` 이후 주문 취소 및 재고 복구 |
| Outbox 운영 | 서비스별 outbox 조회와 due `PENDING` 이벤트 retry |

## 검증 요약

- 백엔드는 서비스별 `mvn test`로 API, Outbox relay, Kafka smoke, Saga handler를 검증합니다.
- 고객/관리자 앱은 Vitest와 production build로 API 호출 모양, 상태 렌더링, 재시도 키 재사용을 검증합니다.
- `./tools/architecture-guard/architecture-guard check`로 schema ownership, Controller 반환 타입, 이벤트 envelope, Outbox table shape를 점검합니다.
- 실제 로컬 E2E는 [Local E2E Runbook](docs/runbooks/local-e2e.md)의 `CARD`, `FAIL_CARD`, `DELAY_CARD`, 지연 결제 취소 시나리오를 기준으로 재현합니다.

## 현재 한계

- Gateway는 헬스체크 중심의 기본 진입점이며, 주요 E2E는 서비스 포트 직접 호출 기준입니다.
- 지연 결제 취소 실제 서비스 E2E, 인증/권한, 동시성 경합 자동 테스트, Kafka 장애 복구 자동화는 후속 확장 범위입니다.
