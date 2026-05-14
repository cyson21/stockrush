# StockRush Customer App

Customer App은 한정 상품 조회, SKU 선택, 쿠폰 견적, 주문 생성, Saga 상태 추적을 하나의 흐름으로 보여주는 React 앱입니다.
`CARD` 성공, `FAIL_CARD` 실패/재고 복구, `DELAY_CARD` 결제 지연 시나리오를 UI에서 직접 실행할 수 있습니다.

상품/재고 조회 API는 `docs/api/catalog-inventory.md`, 주문 생성/조회 API는 `docs/api/customer-orders.md`에 정리되어 있습니다.

## 실행

```bash
npm install
npm run dev
```

기본 주소는 `http://localhost:5173`입니다.

## 환경 변수

기본 개발 모드는 Vite proxy prefix를 사용합니다. 정적 배포 또는 gateway 분리 환경에서는 아래 값을 지정합니다.

| Variable | Default |
|---|---|
| `VITE_API_BASE_URL` | same origin |
| `VITE_CATALOG_API_BASE_URL` | `{VITE_API_BASE_URL}/catalog` |
| `VITE_INVENTORY_API_BASE_URL` | `{VITE_API_BASE_URL}/inventory` |
| `VITE_ORDER_API_BASE_URL` | `{VITE_API_BASE_URL}/orders` |
| `VITE_PROMOTION_API_BASE_URL` | `{VITE_API_BASE_URL}/promotion` |

## 연동 서비스

Vite 개발 서버는 아래 프록시를 사용합니다.

| Prefix | Target |
|---|---|
| `/catalog` | `http://localhost:18081` |
| `/inventory` | `http://localhost:18082` |
| `/orders` | `http://localhost:18083` |
| `/promotion` | `http://localhost:18085` |

Docker 데모 모드에서는 Nginx가 같은 prefix를 compose 내부 서비스로 프록시합니다. 실행법은 `infra/demo/README.md`를 기준으로 합니다.

## 화면 흐름

1. `GET /api/products?status=ON_SALE`로 판매 중 상품을 조회한다.
2. 상품 선택 시 `GET /api/stocks?productCode={productCode}`로 SKU별 재고를 조회한다.
3. 쿠폰 코드가 있으면 `POST /api/coupons/quote`로 할인 금액과 결제 예정 금액을 확인한다.
4. 회원 ID, 수량, 결제수단, 쿠폰 코드를 입력해 `POST /api/orders`를 호출한다.
5. 생성된 주문 ID로 `GET /api/orders/{orderId}`를 polling해 주문 상태와 Saga 상태를 갱신한다.

## 시나리오

| 결제수단 | 확인 흐름 |
|---|---|
| `CARD` | 주문 완료와 Saga 완료 |
| `FAIL_CARD` | 주문 취소와 재고 복구 |
| `DELAY_CARD` | 주문 열린 상태와 Saga 지연 상태 |

## 검증

```bash
npm test
npm run build
```
