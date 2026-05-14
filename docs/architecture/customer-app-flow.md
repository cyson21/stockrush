# Customer App Flow

Customer App은 StockRush의 고객 주문 시나리오를 가장 짧게 시연하는 React/Vite 웹 화면이다. 백엔드 포트폴리오의 핵심인 MSA 경계, idempotency key, Saga 상태 전이, 실패 시나리오를 UI에서 확인할 수 있게 한다. Android/iOS 고객 앱 계획은 [Mobile App Flow](mobile-app-flow.md)에서 별도로 다룬다.

## Scope

- 상품 목록 조회
- 상품별 SKU 재고 조회
- 쿠폰 코드 입력과 할인 견적 조회
- 주문 생성
- 주문 상태 polling
- 결제 성공/실패/지연 시나리오 확인

## Screen Flow

```text
Product List
  -> Stock Selection
  -> Order Form
  -> Coupon Quote
  -> Order Created
  -> Status Tracking
  -> COMPLETED, FAILED, or PAYMENT_DELAYED
```

## API Mapping

| Screen Area | API | Usage |
|---|---|---|
| 상품 목록 | `GET /api/products?status=ON_SALE` | 판매 중 상품명, 상품 코드, 가격 표시 |
| SKU 선택 | `GET /api/stocks?productCode={productCode}` | 주문 가능한 SKU와 가용/예약 수량 표시 |
| 쿠폰 적용 | `POST /api/coupons/quote` | 쿠폰 적용 여부, 할인 금액, 결제 예정 금액 표시 |
| 주문 생성 | `POST /api/orders` | 회원 ID, 결제수단, 쿠폰 코드, SKU, 수량, 단가로 주문 생성 |
| 주문 상태 | `GET /api/orders/{orderId}` | 주문 상태, Saga 상태, 결제수단, 주문 라인 표시 |

## Request Headers

| Header | API | Purpose |
|---|---|---|
| `Idempotency-Key` | `POST /api/orders` | 중복 주문 생성 방지 |
| `X-Correlation-Id` | all app requests | 화면에서 시작한 흐름을 서비스 로그와 이벤트 흐름에서 추적 |

## Runtime Configuration

개발 환경은 Vite proxy prefix를 기본으로 사용한다. 배포 환경에서는 `VITE_API_BASE_URL` 또는 서비스별 base URL을 지정해 gateway나 분리된 서비스 주소로 연결한다.

| Prefix | Service |
|---|---|
| `/catalog` | Catalog Service |
| `/inventory` | Inventory Service |
| `/orders` | Order Service |
| `/promotion` | Promotion Service |

## Status Tracking

Customer App은 주문 생성 직후 `GET /api/orders/{orderId}`를 2초 간격으로 polling한다. `COMPLETED` 또는 `FAILED` Saga 상태를 받으면 polling을 멈춘다. `PAYMENT_DELAYED`는 열린 상태로 표시되며, 상태 조회가 3회 연속 실패하면 자동 조회를 중단하고 사용자에게 오류를 알린다.

## Demo Scenarios

| Payment Method | Expected Direction |
|---|---|
| `CARD` | 재고 선점, 결제 승인, 주문 완료 흐름 |
| `FAIL_CARD` | 재고 선점 후 결제 실패, 주문 취소와 재고 복구 흐름 |
| `DELAY_CARD` | 재고 선점 후 결제 지연, 주문 열린 상태 유지 흐름 |
| `WELCOME10` coupon | 주문 전 할인 견적과 할인/결제 예정 금액 표시 |

## Boundaries

- Customer App은 서비스별 HTTP API를 직접 호출한다.
- 운영용 이벤트 재처리, outbox 상태 조회, DLQ 관리는 Admin App 범위로 둔다.
- 쿠폰 사용 상태는 Promotion Service의 주문 이벤트 consumer가 기록하고, 관리자는 Admin App Coupons 탭에서 조회한다. 배송과 회원 인증은 이후 phase에서 확장한다.
- 이 문서의 Customer App은 웹 시연 클라이언트다. 모바일 앱은 Expo React Native 기반 `apps/mobile-app`으로 후속 확장한다.
