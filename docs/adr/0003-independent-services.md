# ADR 0003: 서비스별 독립 프로젝트 구조 선택

날짜: 2026-05-12  
상태: Accepted

## Context

StockRush는 서비스 경계를 명확히 나누는 MSA 프로젝트다.

Spring Boot 멀티모듈 하나로 모든 서비스를 구성하면 공통 설정과 빌드가 편하지만, 실제 서비스 경계가 흐려질 수 있다. 반대로 서비스별 독립 프로젝트로 구성하면 실행, 설정, 배포, 데이터 소유권을 더 명확하게 표현할 수 있다.

단순히 패키지를 나누는 수준이 아니라, 서비스별 독립 실행과 명확한 책임 경계를 유지하는 것이 중요하다.

## Decision

StockRush는 `services/<service-name>` 아래에 서비스별 독립 Spring Boot 프로젝트를 둔다.

예상 구조:

```text
services/
  gateway/
  auth-service/
  catalog-service/
  inventory-service/
  order-service/
  payment-service/
  promotion-service/
  fulfillment-service/
  notification-worker/
```

공통 코드는 초기에 공유 라이브러리로 분리하지 않는다. 중복이 실제로 의미 있는 비용이 될 때만 별도 공통 모듈을 검토한다.

## Alternatives

### Spring Boot 멀티모듈

장점:

- 한 번에 빌드하기 쉽다.
- 공통 설정을 공유하기 쉽다.
- 초기 개발 속도가 빠르다.

단점:

- 서비스 경계가 패키지 구조 수준으로 약해질 수 있다.
- 서비스별 독립 실행과 독립 배포 감각이 약하다.
- 서로의 내부 코드에 접근하기 쉬워진다.

### 단일 애플리케이션 내부 모듈

장점:

- 가장 빠르게 기능을 만들 수 있다.
- 로컬 디버깅이 쉽다.

단점:

- 이번 프로젝트의 핵심 목표와 맞지 않는다.
- Kafka 기반 서비스 간 이벤트 흐름이 형식적인 요소로 보일 수 있다.

## Consequences

긍정적 결과:

- 서비스별 독립 실행이 가능하다.
- 데이터 소유권과 API 경계가 명확해진다.
- Architecture Guard로 서비스 간 침범을 점검하기 쉽다.
- 서비스 경계와 독립 실행 구조가 코드와 실행 방식에 드러난다.

비용:

- 반복 설정이 늘어난다.
- 로컬 실행 포트와 환경 변수를 관리해야 한다.
- 통합 테스트 구성이 복잡해진다.

## Implementation Notes

- 각 서비스는 별도 `pom.xml`을 가진다.
- 공통 이벤트 envelope는 초기에는 각 서비스에 명시적으로 둔다.
- 중복이 누적되면 `libs/java-common` 도입 여부를 별도 ADR로 판단한다.
- 서비스 간 DB 직접 접근은 금지한다.
- 서비스 간 동기 HTTP 호출은 허용 목록을 문서화한다.
