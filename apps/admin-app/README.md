# StockRush Admin App

관리자용 React/Vite 앱입니다.
상품 등록/수정, SKU 재고 설정, 최근 주문 조회, 지연 결제 취소 요청, 주문 Saga 상세 조회, 서비스별 outbox 이벤트 조회/재시도를 한 화면에서 처리할 수 있습니다.

## 실행

```bash
cd apps/admin-app
npm install
npm run dev
```

기본 주소는 `http://localhost:5174`입니다.

## 환경 변수

기본 개발 모드는 서비스 프록시를 사용하고, 정적 환경에서는 아래 환경 변수를 지정할 수 있습니다.

| 환경 변수 | 설명 |
|---|---|
| `VITE_API_BASE_URL` | 공통 기본 주소 |
| `VITE_CATALOG_API_BASE_URL` | 상품 서비스 주소 (기본: `${VITE_API_BASE_URL}/catalog`) |
| `VITE_ORDER_API_BASE_URL` | 주문 서비스 주소 (기본: `${VITE_API_BASE_URL}/orders`) |
| `VITE_INVENTORY_API_BASE_URL` | 재고 서비스 주소 (기본: `${VITE_API_BASE_URL}/inventory`) |
| `VITE_PAYMENT_API_BASE_URL` | 결제 서비스 주소 (기본: `${VITE_API_BASE_URL}/payment`) |

## 프록시

개발 서버 프록시는 아래 Prefix를 내부적으로 localhost 서비스로 연결합니다.

- `/catalog` -> `http://localhost:18081`
- `/orders` -> `http://localhost:18083`
- `/inventory` -> `http://localhost:18082`
- `/payment` -> `http://localhost:18084`

## 검증

```bash
npm test
npm run build
```
