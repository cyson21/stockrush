# Catalog Admin API

## 상품 등록

`POST /api/admin/products`

### Headers

| Header | Required | Description |
|---|---:|---|
| `Idempotency-Key` | yes | 중복 생성 요청 방지 기준 |
| `X-Correlation-Id` | no | HTTP/Kafka 흐름 추적 기준 |

### Request

```json
{
  "productCode": "NEW-001",
  "name": "Premium Bag",
  "salesStatus": "ON_SALE",
  "listPrice": 35000.00
}
```

### Response

`201 Created` with standard `ApiResponse` shape.

```json
{
  "success": true,
  "data": {
    "productCode": "NEW-001",
    "name": "Premium Bag",
    "status": "ON_SALE",
    "listPrice": 35000.0
  },
  "trace": {
    "correlationId": "corr-admin-product-create"
  }
}
```

## 상품 수정

`PUT /api/admin/products/{productCode}`

### Headers

| Header | Required | Description |
|---|---:|---|
| `Idempotency-Key` | yes | 중복 수정 요청 방지 기준 |
| `X-Correlation-Id` | no | HTTP/Kafka 흐름 추적 기준 |

### Request

```json
{
  "name": "Premium Bag Updated",
  "salesStatus": "STOPPED",
  "listPrice": 33000.00
}
```

### Response

`200 OK` with standard `ApiResponse` shape.

## Error Codes

| HTTP Status | Code | Case |
|---:|---|---|
| 400 | `COMMON_MISSING_IDEMPOTENCY_KEY` | command request is missing `Idempotency-Key` |
| 400 | `CATALOG_INVALID_REQUEST` | request body or path value is invalid |
| 404 | `CATALOG_PRODUCT_NOT_FOUND` | target product does not exist |
| 409 | `CATALOG_DUPLICATE_PRODUCT_CODE` | product code already exists |
| 500 | `CATALOG_DATA_INTEGRITY_ERROR` | unexpected catalog data integrity failure |
