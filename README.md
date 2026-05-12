# StockRush

StockRush는 한정 판매 상황을 다루는 커머스/플랫폼 백엔드 포트폴리오 프로젝트입니다.

이 프로젝트의 목표는 단순 상품/주문 CRUD가 아니라, 5년차 백엔드 개발자로서 설명 가능한 서비스 경계, Kafka 기반 이벤트 처리, 주문 Saga, 재고 동시성 제어, 실패 보상 처리, AI 기반 개발 운영 체계를 함께 보여주는 것입니다.

## 핵심 방향

- MSA 기반 커머스 플랫폼
- Apache Kafka 기반 이벤트 처리
- Order Service 중심 Saga Orchestrator
- Outbox 기반 이벤트 발행 정합성
- Retry / Dead Letter Topic 기반 실패 격리
- CQRS Read Model 기반 조회 분리
- Dev RAG, Project MCP, AI Run Ledger, Architecture Guard 기반 agentic development

## 문서 구조

공개 설명과 내부 실행 기록을 분리합니다.

### 공개 문서

- [ADR 0001: Kafka 기반 MSA 선택](docs/adr/0001-kafka-based-msa.md)
- [ADR 0002: 개발 운영용 RAG 우선 구축](docs/adr/0002-dev-rag-first.md)
- [ADR 0003: 서비스별 독립 프로젝트 구조 선택](docs/adr/0003-independent-services.md)
- [Development Operations Architecture](docs/architecture/development-operations.md)
- [Architecture Guard Rules](docs/architecture/architecture-guard-rules.md)
- [Phase 1 Commerce Foundation](docs/architecture/phase-1-commerce-foundation.md)
- [Customer App Flow](docs/architecture/customer-app-flow.md)
- [Admin App Flow](docs/architecture/admin-app-flow.md)
- [Common API Response](docs/api/common.md)
- [Admin Order API](docs/api/admin-orders.md)
- [Outbox Admin API](docs/api/outbox-admin.md)
- [Event Envelope](docs/architecture/events.md)
- [Outbox and Consumer Idempotency](docs/architecture/outbox.md)
- [AI Development Process](docs/ai-development-process.md)

### 내부 실행 기록


## 예정 아키텍처

```text
Customer App / Admin App
        |
     API Gateway
        |
+-------+-------------+---------------+----------------+
|                     |                                |
Auth Service     Catalog Service                  Order Service
                      |                                |
                Search Service                   Saga Orchestrator
                                                       |
Inventory Service  <---------- Kafka ----------> Payment Service
        |                                              |
Promotion Service                              Fulfillment Service
        |
Notification Worker

Read Models
- order_query_view
- product_search_view
- admin_order_dashboard_view
```

## 개발 운영 방식

StockRush는 기능 구현과 별개로 AI 기반 개발 운영 체계를 프로젝트 내부에 포함합니다.

- `tools/dev-rag`: 프로젝트 규칙, 설계 문서, ADR, API 명세, 코드 요약을 검색해 Context Pack 생성
- `tools/project-mcp`: AI Agent가 프로젝트 상태와 문서를 표준 도구로 조회하는 MCP 서버
- `tools/architecture-guard`: 서비스 경계와 Kafka 이벤트 규칙을 자동 점검하는 guard

공개 포트폴리오에서는 raw 실행 기록 대신 [AI Development Process](docs/ai-development-process.md)에 정리된 검증 흐름과 결과를 중심으로 설명합니다.

## 현재 상태

현재는 Phase 1 커머스 핵심 흐름 위에 고객 주문 앱과 관리자 운영 앱을 연결하는 중입니다.

Phase 0에서 Dev RAG, Project MCP, AI Run Ledger, Architecture Guard 기반을 만들었고, Phase 1에서는 PostgreSQL, Redis, Apache Kafka, Kafka UI, gateway, catalog, inventory, order, payment 서비스 구조를 고정합니다.

로컬 인프라 기준은 [infra/local](infra/local/README.md)에 정리되어 있습니다.
