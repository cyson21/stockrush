# AI Development Process

StockRush는 AI를 코드 생산 보조 도구로만 쓰지 않고, Dev RAG → Project MCP → Spark 역할 분리 → Agent Runner 기록 → 검증 게이트의 운영 흐름으로 연결해 사용한다.

## 목표

- AI 도입이 목표와 설계 기준을 벗어나지 않도록 실행 흐름을 고정한다.
- 필요한 맥락을 검색해 Context Pack으로 고정하고, 증거 기반 결정을 남긴다.
- AI 작업자(Spark worker)와 리뷰어(Spark reviewer) 역할을 분리해 책임을 구분한다.
- 코드, 문서, 운영 정합성은 테스트와 Architecture Guard로 순차 검증한다.
- 실행 기록을 남겨 변경 근거와 검증 결과를 추적 가능하게 만든다.

## 구성 요소

| 구성 요소 | 역할 |
|---|---|
| Dev RAG | ADR, API 명세, 운영 메모 등에서 쿼리 가능한 문맥을 찾아 Context Pack으로 정리 |
| Project MCP Server | 문서 조회, Dev RAG 검색, 검증 명령 조회를 같은 인터페이스로 표준화 |
| Spark worker / Spark reviewer | 구현 단위와 리뷰 단위를 분리하고, 변경 범위 충돌 위험을 줄임 |
| Architecture Guard | 서비스 경계, 이벤트 규칙, 아웃박스 규약을 정적 규칙으로 검사 |

## 작업 흐름

```text
1. 작업 목표 정의
2. Dev RAG로 요구사항, ADR, 관련 API 기준을 묶어 Context Pack 생성
3. Project MCP로 검색 기반 자료·검증 커맨드·권고 파일을 조회
4. Spark worker/reviewer를 분리해 구현 범위와 리뷰 범위를 고정
5. 구현 후 공통 검증 게이트 실행
7. README/TODO/architecture docs 갱신
```

공식 검증 게이트는 다음 순서로 운영한다.

1) 정적/일반 텍스트 정합성 (공백, 용어 금지어, 경량 리뷰)
2) 서비스/도구별 단위 테스트
3) 도메인별 빌드 테스트(필요 시 Maven 또는 앱 테스트)
4) Architecture Guard 실행
5) UI 변경 시 브라우저 확인
6) 실행 기록 최종 검토 후 다음 단계 진행

## 증명 가능한 체크리스트

| 항목 | 기본 증거 |
|---|---|
| Dev RAG, Architecture Guard, Project MCP, Agent Runner 도입 | `2026-05-12-1703-phase-0-dev-ops-tools` |
| 실행 정합성 | 해당 run의 verification에서 각 도구 단위 테스트 `PASS` |
| Maven 검증 | catalog-service, order-service 등에서 `mvn test` 통과 기록 |
| 앱 검증 | admin-app/product 관련 `npm test`, `npm run build` PASS |
| Architecture Guard | 각 run의 `./tools/architecture-guard/architecture-guard check` PASS |
| 브라우저 확인 | admin/customer 화면 렌더링 및 프록시/API 응답 PASS |

## 공개 기준

실행 기록의 원문은 내부 증적이다. 외부 문서에는 공개 가능한 항목만 옮긴다.

- 작업 목표
- 사용한 맥락의 범주
- 변경 파일 요약
- 실행한 검증 명령
- 검증 결과
- 남은 리스크

## Redaction Rule

공개 문서와 공개 가능한 실행 기록에는 아래를 넣지 않는다.

- secret, credential, token, API key
- 회사 계정 또는 비공개 계정 정보
- 로컬 절대 경로
- 비공개 프롬프트 전문
- 현재 프로젝트와 무관한 워크스페이스 정보
- 외부에 공개하면 안 되는 운영 데이터

## 현재 증거


### 1) Phase 0 도구 구현 증거

- Dev RAG, Architecture Guard, Project MCP Server, Agent Runner의 단위 테스트가 모두 실행되어 통과.
- `./tools/architecture-guard/architecture-guard check`도 pass로 기록.
- 공개 문서 정리에서 로컬 경로 제거와 금지어 스캔이 함께 확인됨.



- order-service: `0003-order-persistence-outbox`, `0004-order-create-api`, `0014-order-query-api`, `0016-admin-order-ops-api`
- catalog-service: `0008-catalog-order-saga`, `0648-catalog-admin-api`
- inventory-service: `0006-inventory-payment-consumers`, `0009-inventory-payment-relay`, `0011-inventory-stock-api`, `0013-inventory-reservation-finalization`
- payment-service: `0006-inventory-payment-consumers`, `0009-inventory-payment-relay`, `0012-payment-method-failure`
- admin-app/customer-app: `0015-customer-app-flow`, `0018-admin-app-operations`, `0715-admin-product-stock-app`
- portfolio docs: `0724-public-docs-e2e-ai-process`

### 3) Spark worker/reviewer 분리 증거

- Admin App 카탈로그/재고 구현: worker + reviewer + main의 3자 역할로 진행.
- Catalog 관리자 API 구현: 구현 worker, spec reviewer, quality reviewer가 각각 분리된 항목 존재.
- Customer flow 및 Admin order-ops 작업: 리뷰 전용 worker가 API 설계/통합 위험을 먼저 점검하고 main이 통합 및 최종 검증.

### 4) Verification Gate 증거

- Maven: catalog admin API 구현 run에서 `mvn test` 실행 후 테스트 통과가 기록.
- 앱 테스트: Admin App run에서 `npm test`와 `npm run build`가 PASS.
- Architecture Guard: API, 이벤트, 아웃박스 중심으로 여러 run에서 통과 기록.
- 브라우저 확인: admin-app desktop/390px/320px 화면과 proxy/API 응답을 레이아웃·응답 관점에서 pass 처리.

## 요약

### 1분 요약


### 3분 요약

“저는 StockRush에서 AI를 단발성 코드 생성 도구가 아니라 개발 운영 파이프라인의 한 구성 요소로 사용했습니다. 목적은 빠른 코드 생성 자체가 아니라, 변경의 근거와 검증 과정을 남기는 것입니다.


특히 order/catalog/inventory/payment/admin-app/customer-app 작업을 분리해서 진행했고, Spark worker와 reviewer를 분리해 구현과 리뷰를 병렬 수행하면서 책임 경계를 명확히 했습니다.

모든 주요 단위는 테스트 게이트와 `architecture-guard check` 및 브라우저 확인을 거쳐야만 다음 단계로 넘어가며, 이 구조 덕분에 변경 이유와 검증 결과가 실행 기록에 남습니다.”
