# Mobile App Flow

StockRush 모바일 앱은 Android/iOS 설치형 고객 앱으로 계획한다. 구현 기술은 Expo React Native와 TypeScript를 우선 사용한다. 목적은 네이티브 모바일 UX 자체의 깊이보다, Kafka/Saga/Read Model 백엔드 흐름을 실제 모바일 클라이언트에서 끝까지 시연하는 것이다.

## Scope

- 고객용 앱만 만든다.
- 관리자 기능은 기존 React/Vite Admin App에 유지한다.
- Android와 iOS는 하나의 Expo React Native 코드베이스로 대응한다.
- 초기 배포 목표는 Expo Go 또는 development build에서 로컬/개발 API를 호출하는 시연 가능한 앱이다.

## Screen Flow

```text
[implemented] Product List and SKU Stock Selection
  -> [remaining] Coupon Quote
  -> [remaining] Checkout
  -> [remaining] Order Status Tracking
  -> [remaining] Order History
```

## API Mapping

| Screen | API | Purpose |
|---|---|---|
| Product List | `GET /api/products?status=ON_SALE` | 판매 중 상품 목록 표시 |
| Product Detail | `GET /api/stocks?productCode={productCode}` | SKU별 가용/예약 수량 표시 |
| Coupon Quote | `POST /api/coupons/quote` | 할인 가능 여부와 결제 예정 금액 표시 |
| Checkout | `POST /api/orders` | 주문 생성, 멱등 키와 correlation id 전달 |
| Order Status | `GET /api/orders/{orderId}` | 주문/Saga 상태 polling |
| Order History | `GET /api/read-model/orders?memberId={memberId}` | Read Model 기반 고객 주문 내역 조회 |

## Client Architecture

Current scaffold:

```text
apps/mobile-app
  App.tsx
  app.json
  package.json
  src/api
    catalog.ts
    inventory.ts
    promotion.ts
    orders.ts
    readModel.ts
  src/config
    runtime.ts
  src/screens
    ProductListScreen.tsx
  src/types
    api.ts
```

Remaining screen modules:

```text
apps/mobile-app
  src/screens
    CheckoutScreen.tsx
    OrderStatusScreen.tsx
    OrderHistoryScreen.tsx
  src/components
```

API 호출, 타입, 화면 컴포넌트를 분리한다. 앱 내부에는 주문 상태 전이 판단을 넣지 않고, 서버 응답의 `status`와 `sagaStatus`를 표시한다.

## Runtime Configuration

모바일 시뮬레이터와 실제 기기는 `localhost` 해석 방식이 다르므로 API base URL을 환경값으로 분리한다.

| Runtime | API Base URL Example |
|---|---|
| iOS Simulator | `http://localhost:18080` |
| Android Emulator | `http://10.0.2.2:18080` |
| Physical Device | `http://{host-lan-ip}:18080` |

모바일 앱은 Gateway를 기본 진입점으로 쓴다. 필요한 Promotion quote와 Read Model order history route는 Gateway에 연결되어 있다.

## UX Rules

- 첫 화면은 상품 목록이다.
- 주문 생성 화면은 결제수단 `CARD`, `FAIL_CARD`, `DELAY_CARD`를 선택할 수 있게 한다.
- 주문 생성 후 상태 화면은 2초 간격 polling을 사용한다.
- `COMPLETED`, `FAILED`, `PAYMENT_DELAYED`에서는 자동 polling을 멈춘다.
- 주문 내역은 Read Model projection이라 최신 반영이 늦을 수 있음을 새로고침 액션으로 보완한다.

## Verification

- 현재 scaffold는 `node apps/mobile-app/scripts/validate-scaffold.mjs`로 구조와 API base URL 기준을 검증한다.
- `ProductListScreen.test.tsx`는 상품 목록 조회, 선택 상품 SKU 재고 조회, 상품 목록 오류와 재시도, SKU 재고 오류와 재시도, 연속 선택 시 늦은 재고 응답 무시 상태를 검증한다.
- 의존성 설치 후 TypeScript typecheck를 실행한다.
- Expo start로 Android/iOS 중 최소 한 환경에서 수동 smoke를 수행한다.
- 백엔드 E2E와 혼동하지 않도록 모바일 검증 결과는 별도 AI Run Ledger에 기록한다.

## Out of Scope

- 관리자 모바일 앱
- 앱스토어/플레이스토어 배포
- 푸시 알림
- 생체 인증
- 오프라인 캐시
- Kotlin/Swift 이중 네이티브 구현
