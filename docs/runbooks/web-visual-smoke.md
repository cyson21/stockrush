# Web Visual Smoke Runbook

웹 화면 정리 이후 Customer/Admin App 대표 화면을 브라우저에서 확인하고 스크린샷으로 남기는 절차다.

## Scope

- Customer App desktop/mobile 화면 캡처
- Admin App Dashboard와 Outbox 화면 캡처
- 캡처 파일 저장 위치 표준화
- 주요 텍스트 잘림, 빈 화면, 오류 banner 노출 여부 확인

## Runtime

기본 기준은 full Docker demo runtime이다.

```bash
./scripts/demo-up.sh
./scripts/demo-smoke.sh --skip-burst
```

개발 중 UI만 빠르게 확인할 때는 web app dev server를 사용할 수 있다.

```bash
npm --prefix apps/customer-app run dev
npm --prefix apps/admin-app run dev
```

## Target URLs

| App | Demo URL | Dev URL |
|---|---|---|
| Customer App | `http://localhost:15173` | `http://localhost:5173` |
| Admin App | `http://localhost:15174` | `http://localhost:5174` |

## Screenshot Targets

| File | Viewport | Target |
|---|---|---|
| `docs/assets/screenshots/customer-home-desktop.png` | 1440 x 1000 | Customer product, checkout, order status |
| `docs/assets/screenshots/customer-home-mobile.png` | 390 x 1200 | Customer stacked mobile layout |
| `docs/assets/screenshots/admin-dashboard-desktop.png` | 1440 x 1000 | Admin Dashboard metrics and filters |
| `docs/assets/screenshots/admin-outbox-desktop.png` | 1440 x 1000 | Admin Outbox operations |

## Visual Checks

- 화면이 흰 빈 페이지로 저장되지 않는다.
- 상단 제목, 주요 카드, form label, table header가 보인다.
- 버튼 텍스트가 잘리지 않는다.
- status chip 색상이 상태별로 구분된다.
- mobile width에서 가로 overflow가 생기지 않는다.
- 민감한 token 전문이 화면에 노출되지 않는다.

## Verification

스크린샷 생성 전후로 아래 검증을 실행한다.

```bash
npm --prefix apps/customer-app test -- --run
npm --prefix apps/customer-app run build
npm --prefix apps/admin-app test -- --run
npm --prefix apps/admin-app run build
```

demo runtime 기준 캡처라면 smoke도 함께 실행한다.

```bash
./scripts/demo-smoke.sh --skip-burst
```

## Cleanup

demo runtime을 사용한 경우 작업 후 컨테이너를 정리한다.

```bash
./scripts/demo-down.sh
```
