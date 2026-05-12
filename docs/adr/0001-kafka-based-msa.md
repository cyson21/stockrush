# ADR 0001: Kafka 기반 MSA 선택

날짜: 2026-05-12  
상태: Accepted

## Context

StockRush는 이직용 백엔드 포트폴리오 프로젝트다.

단순 CRUD 또는 단일 서버 구조만으로는 5년차 백엔드 개발자에게 기대되는 설계 역량을 충분히 보여주기 어렵다. 커머스 도메인에서는 주문, 재고, 결제, 프로모션이 서로 다른 변경 이유와 정합성 요구를 가진다.

특히 한정 판매 상황에서는 다음 문제가 중요하다.

- 재고 선점과 확정의 동시성
- 결제 실패 시 재고와 쿠폰 복구
- 주문 상태 전이
- 이벤트 재처리
- 장애 발생 시 실패 격리
- 조회 모델 분리

## Decision

StockRush는 Apache Kafka 기반 이벤트 MSA로 설계한다.

초기 핵심 서비스는 다음 경계로 나눈다.

- API Gateway
- Catalog Service
- Inventory Service
- Order Service
- Payment Service
- Promotion Service
- Fulfillment Service
- Notification Worker

Order Service는 Saga Orchestrator 역할을 맡고, 각 서비스는 Kafka 이벤트를 통해 주문 흐름을 이어간다.

## Alternatives

### Monolith

장점:

- 구현 속도가 빠르다.
- 로컬 실행이 단순하다.
- 트랜잭션 경계가 쉽다.

단점:

- 서비스 경계 설계 역량이 드러나지 않는다.
- Kafka, Saga, Outbox, 보상 처리 경험을 보여주기 어렵다.
- 포트폴리오 차별성이 약하다.

### MSA-lite with REST only

장점:

- 서비스 분리 구조를 빠르게 보여줄 수 있다.
- Kafka 운영 부담이 없다.

단점:

- 서비스 간 결합이 커지기 쉽다.
- 재시도, 멱등성, 실패 격리 설계가 약해질 수 있다.
- 커머스 주문 흐름의 비동기 특성을 충분히 표현하기 어렵다.

### Redpanda

장점:

- Kafka 호환 API를 제공한다.
- 로컬 개발이 상대적으로 가볍다.

단점:

- 사용자가 실제 Kafka 경험을 쌓고 싶다는 목표와 어긋난다.
- 포트폴리오에서 Kafka 직접 사용 경험으로 설명하기에는 약하다.

## Consequences

긍정적 결과:

- Kafka topic, consumer group, partition key, retry, DLQ를 직접 다룰 수 있다.
- Saga, Outbox, 멱등 Consumer를 포트폴리오 핵심 역량으로 설명할 수 있다.
- 장애 복구 흐름과 운영 화면을 자연스럽게 만들 수 있다.

비용:

- 로컬 인프라 구성이 복잡해진다.
- 테스트 범위가 넓어진다.
- 서비스 수가 늘어나 초기 구현 속도가 느려질 수 있다.

## Implementation Notes

- Kafka는 Docker Compose 기반 KRaft mode로 실행한다.
- 개발 초기에는 단일 PostgreSQL 인스턴스 안에 서비스별 schema를 둔다.
- 주문 schema는 PostgreSQL 예약어와 구분하기 위해 `orders`로 둔다.
- 이벤트는 공통 envelope를 사용한다.
- 주문 이벤트의 partition key는 `orderId`를 기본으로 한다.
- 실패 이벤트는 retry topic과 dead letter topic으로 분리한다.
