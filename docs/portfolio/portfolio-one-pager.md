# StockRush One Pager

## 한 줄 소개

StockRush는 한정 판매 커머스에서 주문, 재고, 결제, 쿠폰, 출고, 조회 모델을 분리했을 때 생기는 상태 수렴과 운영 복구 문제를 MSA, Kafka, Saga, Outbox로 다룬 백엔드 중심 프로젝트입니다.

## 만든 이유

단순 CRUD보다 상태 수렴과 운영 복구가 필요한 백엔드 흐름을 보여주는 쪽이 프로젝트 목적에 더 맞다고 판단했습니다. 그래서 주문이 생성된 뒤 재고 선점, 결제 승인, 실패 복구, 지연 결제 취소, Outbox 재시도, 관리자 운영 화면까지 이어지는 흐름을 하나의 데모로 묶었습니다.

이 프로젝트에서 보고 싶은 지점은 화면의 양보다 백엔드 흐름입니다. 요청이 몰렸을 때 상태가 어떻게 수렴하는지, 메시지가 중복되거나 발행이 실패했을 때 어떻게 복구하는지, AI 기반 개발을 어떤 검증 체계로 통제했는지를 보여주는 데 집중했습니다.

## 아키텍처 요약

![StockRush 아키텍처](../assets/architecture/stockrush-architecture.png)

외부 진입점은 Gateway로 제한했습니다. 고객 웹, 관리자 웹, Expo 모바일 앱은 Keycloak OIDC/PKCE로 로그인하고, Gateway는 JWT 검증과 역할 기반 라우팅, 신뢰 가능한 내부 헤더 전파를 담당합니다.

도메인은 Catalog, Inventory, Order, Payment, Promotion, Fulfillment, Read Model로 나눴습니다. PostgreSQL은 하나의 인스턴스를 사용하되 서비스별 schema를 분리했고, 서비스 간 상태 변화는 Kafka command/event와 Outbox relay로 연결했습니다.

## 핵심 흐름

![주문 Saga 흐름](../assets/architecture/stockrush-saga-flow.png)

고객이 주문을 만들면 Order Service가 주문과 `OrderCreated` Outbox 이벤트를 같은 DB 트랜잭션에 저장합니다. 이후 Kafka를 통해 Inventory가 재고를 선점하고, Payment가 결제를 승인하거나 실패/지연 처리합니다. 결과 이벤트가 다시 Order Service로 돌아오면 주문은 `CONFIRMED`, `CANCELLED`, `PAYMENT_DELAYED` 중 하나로 수렴합니다.

`DELAY_CARD` 주문은 관리자 화면에서 취소할 수 있습니다. 취소 요청은 Payment cancel command로 이어지고, 최종적으로 주문 취소와 재고 복구까지 이어집니다. Promotion, Fulfillment, Read Model은 주문 이벤트를 소비해 쿠폰 사용 상태, 출고 준비 요청, 고객/관리자 조회 모델을 갱신합니다.

## 복구와 보안

![Outbox 복구 흐름](../assets/architecture/stockrush-outbox-recovery.png)

Outbox는 발행 실패를 숨기지 않고 운영자가 볼 수 있는 상태로 남깁니다. relay는 `PENDING` 이벤트를 발행하고, 실패하면 재시도 또는 `FAILED`로 전환합니다. 관리자 화면에서는 failed 이벤트를 다시 처리할 수 있고, consumer는 processed event 저장으로 중복 수신을 방어합니다.

![보안 경계](../assets/architecture/stockrush-security-boundary.png)

클라이언트가 보낸 `memberId`나 operator header는 권한 기준으로 믿지 않습니다. Gateway가 JWT를 검증한 뒤 subject, role, operator 정보를 내부 헤더로 다시 만들고, 고객 주문 상세와 주문 내역은 인증 subject 기준으로 소유권을 검사합니다.

## 구현 범위

| 영역 | 내용 |
|---|---|
| Backend | Gateway, Catalog, Inventory, Order, Payment, Promotion, Fulfillment, Read Model |
| Messaging | Kafka topic, Outbox relay, consumer 중복 처리, failed requeue |
| Security | Keycloak OIDC/PKCE, Gateway JWT 검증, `ROLE_CUSTOMER`/`ROLE_ADMIN`, 주문 소유권 검사 |
| Web | 고객 주문 흐름, 관리자 상품/재고/주문/Saga/Outbox/쿠폰/출고 화면 |
| Mobile | Expo React Native 기반 상품 조회, 쿠폰 견적, 보호 주문 생성, 주문 내역 |
| Runtime | Docker Compose 데모, Windows PowerShell 스크립트, 선택형 kind Kubernetes |
| CI/CD | GitHub Actions, GHCR 이미지 발행, Trivy scan, secret scan, AWS 사용 차단 |

![CI/CD와 실행 환경](../assets/architecture/stockrush-cicd-runtime.png)

## 보안/증거 정합성

- Gateway는 외부 진입점이며, 고객·관리자 앱은 모두 Gateway를 통해 인증/권한을 거쳐 서비스에 접근합니다.
- 내부 서비스는 클라이언트로부터 전달된 식별자만으로 결정을 내리지 않고, Gateway/서비스 간 신뢰 경계 및 소유권 검증을 통해 처리합니다.
- 이번 보강은 화면 문구 정리가 아니라 API boundary hardening입니다. 고객 주문 body의 `memberId` 제거, 데모 backend port 노출 차단, Nginx 직접 backend proxy 차단, 고객 route trusted subject 회귀 검사를 함께 묶었습니다.

## 검증한 시나리오

| 시나리오 | 확인 내용 |
|---|---|
| 정상 주문 | `CARD` 주문이 `CONFIRMED/COMPLETED`로 수렴 |
| 결제 실패 | `FAIL_CARD` 주문이 취소되고 예약 재고가 복구 |
| 지연 결제 | `DELAY_CARD` 주문을 관리자 취소로 복구 |
| 동일 SKU 동시 주문 | 제한된 재고보다 많이 확정되지 않는지 확인 |
| 대량 요청 + 멱등성 | replay 요청이 중복 주문이나 중복 부작용을 만들지 않는지 확인 |
| Kafka 일시 중단 | broker pause 중 Outbox 대기, unpause 후 상태 수렴 확인 |
| Gateway 보안 | 인증 없음 `401`, 권한 부족 `403`, 다른 고객 주문 조회 차단 |
| Kubernetes smoke | kind 환경에서 Gateway, 웹앱, Keycloak endpoint 확인 |

## AI 기반 개발 운영

AI는 단순 코드 생성기가 아니라 개발 운영 체계 안에서 사용했습니다.

- Dev RAG: ADR, API 문서, 실행 기록을 검색 가능한 문맥으로 관리
- Project MCP: 문서와 검증 명령 조회를 표준화
- Architecture Guard: 서비스 경계, 이벤트 envelope, Gateway 보안 규칙을 자동 점검
- Spark worker/reviewer: 구현과 리뷰를 나눠 독립적으로 검토

핵심은 생성 속도보다 변경의 추적 가능성입니다. 어떤 문맥으로 작업했고, 어떤 파일이 바뀌었고, 어떤 검증을 통과했는지 남기는 방식으로 운영했습니다.

## 남겨둔 확장 지점

- Fulfillment의 carrier, label, tracking 상태
- OpenTelemetry, Prometheus, Grafana 기반 관측
- iOS simulator 또는 development build 기반 추가 모바일 증거
- consumer lag, 처리량, 병렬성에 대한 부하 실험
