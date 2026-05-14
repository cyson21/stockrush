# Admin App Flow

Admin App은 StockRush 운영 시나리오를 시연하는 React/Vite 웹앱이다. Read Model 기반 운영 지표, 상품/재고 운영, 주문 Saga 상태, 서비스별 outbox 상태를 같은 앱에서 확인하고, due 상태의 pending 이벤트 재시도와 failed 이벤트 재처리 준비를 수동으로 실행할 수 있게 한다. 모바일 확장은 고객 앱에 한정하고, 관리자 기능은 웹앱에 유지한다.

## Scope

- Read Model 기반 주문 Dashboard
- 최근 주문 목록
- 주문별 Saga 상세 조회
- 지연 결제 주문 취소 요청
- 상품 목록 조회
- 관리자 상품 등록/수정
- 상품코드별 재고 조회
- SKU 재고 수량 설정
- order, inventory, payment outbox 이벤트 조회
- 서비스별 outbox relay 수동 retry
- 서비스별 failed outbox requeue

## Screen Flow

```text
Dashboard
  -> Order KPI Summary
  -> Recent Projected Orders
  -> Cancellation Reason Summary

Orders
  -> Recent Order List
  -> Saga Detail
  -> Delayed Payment Cancel Request

Outbox
  -> Service Selection
  -> Pending/Failed Event List
  -> Manual Retry
  -> Failed Requeue
  -> Event List Refresh

Products
  -> ON_SALE Product List
  -> Product Create / Update
  -> Stock Search by Product Code
  -> Stock Quantity Upsert by SKU
```

## API Mapping

| Screen Area | API | Usage |
|---|---|---|
| Dashboard | `GET /api/read-model/admin/orders` | Read Model 기반 주문 수, 상태별 지표, 최근 주문, 취소 사유 요약 |
| 주문 목록 | `GET /api/admin/orders` | 최근 주문 상태와 Saga 상태 요약 |
| Saga 상세 | `GET /api/admin/orders/{orderId}/saga` | 실패 시각, 마지막 이벤트, 비즈니스 실패 사유, 기술 오류 |
| 지연 결제 취소 | `POST /api/admin/orders/{orderId}/cancel` | `PAYMENT_DELAYED` 주문의 결제 취소 command 발행 |
| 상품 목록 | `GET /api/products?status=ON_SALE` | 운영 대상 상품 선택과 재고 조회 기준 |
| 상품 등록 | `POST /api/admin/products` | 상품코드, 상품명, 판매 상태, 판매가 등록 |
| 상품 수정 | `PUT /api/admin/products/{productCode}` | 상품명, 판매 상태, 판매가 수정 |
| 재고 목록 | `GET /api/stocks?productCode={productCode}` | 상품별 SKU 재고와 예약 수량 확인 |
| 재고 설정 | `PUT /api/stocks/{skuId}` | SKU 기준 재고 수량 설정 또는 신규 SKU 초기화 |
| Outbox 목록 | `GET /api/admin/outbox-services/{service}/events` | 각 서비스의 pending/failed 이벤트 |
| Outbox retry | `POST /api/admin/outbox-services/{service}/events/retry` | 각 서비스 relay를 수동 실행 |
| Outbox failed requeue | `POST /api/admin/outbox-services/{service}/events/failed/requeue` | failed 이벤트를 pending 상태로 되돌려 다음 relay 대상에 포함 |

## Service Routing

Vite 개발 서버는 서비스별 prefix를 backend service로 proxy한다.

| Prefix | Target |
|---|---|
| `/catalog` | `http://localhost:18081` |
| `/orders` | `http://localhost:18083` |
| `/inventory` | `http://localhost:18082` |
| `/payment` | `http://localhost:18084` |
| `/api/admin/outbox-services` | `http://localhost:18080` |
| `/api/read-model` | `http://localhost:18080` |

배포 또는 gateway 환경에서는 서비스별 API는 `VITE_API_BASE_URL` 또는 서비스별 base URL 환경 변수로 주소를 바꾼다. Outbox 운영 API와 Read Model Gateway route는 `VITE_GATEWAY_API_BASE_URL`이 있으면 그 주소를 사용하고, 없으면 same-origin Gateway path를 사용한다.

## Boundaries

- Admin App은 HTTP API만 호출한다.
- 한 서비스가 다른 서비스 DB schema를 직접 읽지 않는다.
- Dashboard는 Read Model projection만 조회하고 주문 처리 명령을 실행하지 않는다.
- 상품 등록/수정 요청은 `Idempotency-Key`를 전달한다.
- 지연 결제 취소 요청은 `Idempotency-Key`를 전달하고, `PAYMENT_DELAYED` 주문에만 노출한다.
- 동일 상품 등록/수정 입력을 실패 후 재시도하면 같은 멱등 키를 재사용한다.
- 지연 결제 취소 요청도 실패 후 재시도하면 주문별 같은 멱등 키를 재사용한다.
- 수동 retry는 existing relay를 실행한다.
- failed requeue는 상태를 `PENDING`으로 되돌리고 retry count와 다음 시도 시각, 오류 메시지만 초기화한다.
- Admin App은 outbox retry/requeue 요청에 `X-Operator-Id: admin-app`을 전달하고, 서비스는 감사 로그에 요청 정보를 저장한다.
- 인증과 권한은 이후 phase에서 추가한다.
