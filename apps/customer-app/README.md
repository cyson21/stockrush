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

기본 개발 모드는 Vite proxy를 통해 Gateway로 요청합니다. 정적 배포 또는 Gateway 분리 환경에서는 단일 Gateway base URL만 지정합니다.

| Variable | Default |
|---|---|
| `VITE_API_BASE_URL` | same origin |
| `VITE_AUTH_ISSUER` | `http://localhost:28088/realms/stockrush` |
| `VITE_AUTH_CLIENT_ID` | `stockrush-customer-web` |
| `VITE_AUTH_SCOPE` | `openid profile email` |
| `VITE_AUTH_REDIRECT_URI` | current page URL |

## 연동 서비스

Vite 개발 서버는 아래 프록시를 사용합니다.

| Prefix | Target |
|---|---|
| `/api/products` | `http://localhost:18080` |
| `/api/stocks` | `http://localhost:18080` |
| `/api/orders` | `http://localhost:18080` |
| `/api/coupons` | `http://localhost:18080` |

Docker 데모 모드에서도 고객 앱은 Gateway 공개/보호 경로만 호출합니다. service-local 직접 호출은 내부/dev 확인용이며 공개 시연 진입점으로 쓰지 않습니다. 실행법은 `infra/demo/README.md`를 기준으로 합니다.

## 화면 흐름

1. `GET /api/products?status=ON_SALE&q={query}`로 판매 중 상품을 조회하고, 검색어가 비어 있으면 전체 판매 중 상품을 보여준다.
2. 상품 선택 시 `GET /api/stocks?productCode={productCode}`로 SKU별 재고를 조회한다.
3. 쿠폰 코드가 있으면 `POST /api/coupons/quote`로 할인 금액과 결제 예정 금액을 확인한다.
4. 로그인 후 수량, 결제수단, 쿠폰 코드를 입력해 Gateway `POST /api/orders`를 호출한다. 고객 식별자는 bearer token에서 Gateway가 만든 trusted subject를 사용하며 body로 보내지 않는다.
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
