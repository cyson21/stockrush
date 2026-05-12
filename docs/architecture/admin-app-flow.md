# Admin App Flow

Admin App은 StockRush 운영 시나리오를 시연하는 React 앱이다. 주문 Saga 상태와 서비스별 outbox 상태를 같은 화면에서 확인하고, due 상태의 pending 이벤트를 수동으로 재시도할 수 있게 한다.

## Scope

- 최근 주문 목록
- 주문별 Saga 상세 조회
- order, inventory, payment outbox 이벤트 조회
- 서비스별 outbox relay 수동 retry

## Screen Flow

```text
Orders
  -> Recent Order List
  -> Saga Detail

Outbox
  -> Service Selection
  -> Pending/Failed Event List
  -> Manual Retry
  -> Event List Refresh
```

## API Mapping

| Screen Area | API | Usage |
|---|---|---|
| 주문 목록 | `GET /api/admin/orders` | 최근 주문 상태와 Saga 상태 요약 |
| Saga 상세 | `GET /api/admin/orders/{orderId}/saga` | 실패 시각, 마지막 이벤트, 비즈니스 실패 사유, 기술 오류 |
| Outbox 목록 | `GET /api/admin/outbox-events` | 각 서비스의 pending/failed 이벤트 |
| Outbox retry | `POST /api/admin/outbox-events/retry` | 각 서비스 relay를 수동 실행 |

## Service Routing

Vite 개발 서버는 서비스별 prefix를 backend service로 proxy한다.

| Prefix | Target |
|---|---|
| `/orders` | `http://localhost:18083` |
| `/inventory` | `http://localhost:18082` |
| `/payment` | `http://localhost:18084` |

배포 또는 gateway 환경에서는 `VITE_API_BASE_URL` 또는 서비스별 base URL 환경 변수로 주소를 바꾼다.

## Boundaries

- Admin App은 HTTP API만 호출한다.
- 한 서비스가 다른 서비스 DB schema를 직접 읽지 않는다.
- 수동 retry는 existing relay를 실행하는 수준으로 제한한다.
- 인증과 권한은 이후 phase에서 추가한다.
