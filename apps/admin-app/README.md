# StockRush Admin App

관리자용 React/Vite 앱입니다.
Read Model 기반 주문 Dashboard, 쿠폰 사용 이력, 출고 요청 이력, 상품 등록/수정, SKU 재고 설정, 최근 주문 조회, 지연 결제 취소 요청, 주문 Saga 상세 조회, 서비스별 outbox 이벤트 조회/재시도/requeue를 한 화면에서 처리할 수 있습니다.
Read Model Dashboard는 주문 ID, 회원 ID, 주문 상태, Saga 상태, 쿠폰 코드 조건 검색을 지원합니다.
Outbox 운영 요청의 operator identity는 Gateway가 인증된 관리자 token에서 내부 헤더로 전달합니다.

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
| `VITE_API_BASE_URL` | 서비스별 prefix 앞에 붙는 공통 기본 주소 |
| `VITE_GATEWAY_API_BASE_URL` | Gateway 주소. 보호된 관리자 API에 사용하며, 없으면 same-origin 경로를 사용 |
| `VITE_AUTH_ISSUER` | `http://localhost:28088/realms/stockrush` |
| `VITE_AUTH_CLIENT_ID` | `stockrush-admin-web` |

## 프록시

개발 서버 프록시는 아래 Prefix를 내부적으로 localhost 서비스로 연결합니다.

- `/api/admin` -> `http://localhost:18080`
- `/api/admin/outbox-services` -> `http://localhost:18080`
- `/api/admin/coupon-usages` -> `http://localhost:18080`
- `/api/admin/fulfillment-requests` -> `http://localhost:18080`
- `/api/read-model` -> `http://localhost:18080`

Docker 데모 모드에서는 Nginx가 같은 prefix를 compose 내부 서비스와 Gateway로 프록시합니다. 실행법은 `infra/demo/README.md`를 기준으로 합니다.

## 검증

```bash
npm test
npm run build
```
