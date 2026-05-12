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

## 주요 문서

- [ADR 0001: Kafka 기반 MSA 선택](docs/adr/0001-kafka-based-msa.md)
- [ADR 0002: 개발 운영용 RAG 우선 구축](docs/adr/0002-dev-rag-first.md)
- [ADR 0003: 서비스별 독립 프로젝트 구조 선택](docs/adr/0003-independent-services.md)
- [Development Operations Architecture](docs/architecture/development-operations.md)
- [Dev RAG Design](docs/dev-rag/design.md)
- [Architecture Guard Rules](docs/architecture/architecture-guard-rules.md)
- [Master Implementation Plan](docs/superpowers/plans/2026-05-12-stockrush-master-plan.md)
- [Phase 0 Implementation Plan](docs/superpowers/plans/2026-05-12-phase-0-development-operations-plan.md)

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

## 현재 상태

현재는 설계 및 개발 운영 기반을 구축하는 Phase 0 단계입니다.

첫 구현 목표는 서비스 코드가 아니라, 이후 구현을 안정적으로 진행하기 위한 문서, 의사결정 기록, Dev RAG 최소 설계, AI Run Ledger, Architecture Guard 기준을 고정하는 것입니다.
