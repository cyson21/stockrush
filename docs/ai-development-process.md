# AI Development Process

StockRush는 AI를 단순 코드 생성 도구로 쓰지 않고, 추적 가능한 개발 운영 흐름 안에서 사용한다.

## 목표

- 작업마다 필요한 맥락을 검색해 Context Pack으로 고정한다.
- AI Agent의 역할과 파일 책임을 명확히 나눈다.
- 생성된 코드와 문서는 테스트와 Architecture Guard로 검증한다.
- 작업 결과는 사람이 검토할 수 있는 실행 기록으로 남긴다.

## 구성 요소

| 구성 요소 | 역할 |
|---|---|
| Dev RAG | 프로젝트 문서, ADR, 설계 기준, 코드 요약을 검색해 작업용 Context Pack 생성 |
| Project MCP Server | AI Agent가 문서 조회, Dev RAG 검색, Architecture Guard 실행을 표준 도구로 호출 |
| Agent Runner | 작업 목표, 계획, 변경 파일, 검증 결과를 실행 기록으로 생성 |
| Architecture Guard | 서비스 경계와 Kafka 이벤트 규칙을 자동 점검 |

## 작업 흐름

```text
작업 목표 정의
 -> Context Pack 생성
 -> Agent 역할과 파일 책임 분리
 -> 구현
 -> 테스트 실행
 -> Architecture Guard 실행
 -> 실행 기록 갱신
 -> 문서와 TODO 갱신
```

## 검증 기준

현재 Phase 0 도구는 아래 검증을 통과해야 한다.

| 영역 | 검증 |
|---|---|
| Dev RAG | `python -m unittest discover tools/dev-rag/tests` |
| Architecture Guard | `python -m unittest discover tools/architecture-guard/tests` |
| Project MCP Server | `python -m unittest discover tools/project-mcp/tests` |
| Agent Runner | `python -m unittest discover tools/agent-runner/tests` |
| Architecture Guard 실행 | `./tools/architecture-guard/architecture-guard check` |

## 공개 기준

raw 실행 기록은 내부 증적이다. 포트폴리오에서는 아래 항목만 요약한다.

- 작업 목표
- 사용한 맥락의 범주
- 변경 파일 요약
- 실행한 검증 명령
- 검증 결과
- 남은 리스크

## Redaction Rule

공개 문서와 공개 가능한 실행 기록에는 아래 내용을 남기지 않는다.

- secret, credential, token, API key
- 회사 계정 또는 비공개 계정 정보
- 로컬 절대 경로
- 비공개 프롬프트 전문
- 현재 프로젝트와 무관한 워크스페이스 정보
- 외부에 공개하면 안 되는 운영 데이터

## 현재 증거

Phase 0에서 생성된 첫 실행 기록은 내부 증적으로 유지한다.

요약:

- Dev RAG CLI 구현
- Architecture Guard CLI 구현
- Project MCP Server 구현
- Agent Runner CLI 구현
- 각 도구의 단위 테스트 통과
- Architecture Guard repo check 통과
