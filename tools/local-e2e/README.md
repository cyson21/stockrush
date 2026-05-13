# Local E2E Tools

이 디렉토리는 이미 기동된 StockRush 로컬 서비스에 대해 반복 가능한 E2E 확인을 수행하는 도구를 둔다.

## same-sku-concurrency

동일 SKU에 대해 여러 주문을 동시에 생성한 뒤, 서비스별 outbox retry API를 호출해 최종 상태를 확인한다.

```bash
./tools/local-e2e/local-e2e same-sku-concurrency \
  --orders 6 \
  --initial-stock 3 \
  --quantity 1 \
  --max-attempts 12
```

기본 포트는 아래 서비스를 사용한다.

| Service | URL |
|---|---|
| Catalog | `http://localhost:18081` |
| Inventory | `http://localhost:18082` |
| Order | `http://localhost:18083` |
| Payment | `http://localhost:18084` |

## 검증 기준

- 주문 생성 요청 6건을 같은 SKU로 병렬 호출한다.
- 초기 재고 3개 기준으로 `CONFIRMED/COMPLETED` 3건, `CANCELLED/FAILED` 3건을 기대한다.
- 최종 재고는 `availableQuantity=0`, `reservedQuantity=0`이어야 한다.
- 이번 실행으로 증가한 Order/Inventory/Payment `PENDING` outbox가 없어야 한다.

이 도구는 로컬 최종 상태 회귀 검증용이다. Kafka consumer 병렬성, broker 장애, 외부 부하 벤치마크까지 증명하는 테스트는 아니다.

## 주의 사항

- 기본값은 실행 전 기존 `PENDING` outbox가 있으면 중단한다.
- `--allow-existing-pending`은 기존 대기 row를 허용하되, 실행 전후 증가분을 기준으로 실패 여부를 판단한다.
- product code와 sku id는 실행마다 `CONC-E2E-YYYYMMDDHHMMSS-xxxxxxxx` 형태로 생성한다.
