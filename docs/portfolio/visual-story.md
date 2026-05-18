# StockRush Visual Story

포트폴리오 설명에서 자주 쓰는 그림만 모았다. README에서는 대표 이미지와 링크만 노출하고, 면접이나 Notion 정리에서는 이 문서를 기준으로 필요한 이미지를 가져간다.

## 1. 전체 구조

![StockRush 아키텍처](../assets/architecture/stockrush-architecture.png)

Gateway, Keycloak, 도메인 서비스, Kafka, Outbox, PostgreSQL, 실행 환경을 한 장에 배치한 대표 이미지다. 처음 설명할 때는 이 그림으로 전체 경계를 잡는다.

## 2. 주문 Saga

![주문 Saga 흐름](../assets/architecture/stockrush-saga-flow.png)

정상 주문은 `OrderCreated -> Reserved -> Authorized -> Confirmed`로 수렴한다. 실패 흐름은 재고 부족, 결제 실패, 지연 결제, 관리자 취소를 같은 상태 전이 흐름 안에서 설명한다.

## 3. Outbox 복구

![Outbox 복구 흐름](../assets/architecture/stockrush-outbox-recovery.png)

Outbox row가 relay를 거쳐 Kafka로 발행되고, 실패 시 retry/requeue와 감사 기록으로 이어지는 구조다. 메시지 중복은 consumer의 processed event 저장으로 방어한다.

## 4. 보안 경계

![보안 경계](../assets/architecture/stockrush-security-boundary.png)

클라이언트가 보낸 식별자를 권한 기준으로 믿지 않고, Gateway가 JWT를 검증한 뒤 내부 헤더를 다시 만드는 흐름이다. 고객 주문 소유권 검사와 관리자 감사 주체 전파를 함께 설명한다.

## 5. CI/CD와 실행 환경

![CI/CD와 실행 환경](../assets/architecture/stockrush-cicd-runtime.png)

GitHub Actions에서 테스트와 보안 검사를 수행하고, GHCR 이미지를 발행한 뒤 Docker Compose나 kind로 로컬에서 재현하는 흐름이다. 개인 AWS 없이도 빌드, 배포, smoke 단계를 분리해 설명할 수 있다.
