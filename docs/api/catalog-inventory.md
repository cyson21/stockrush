# Catalog and Inventory API

고객 앱의 상품 탐색과 SKU 재고 조회, 관리자 앱의 SKU 수량 설정에 필요한 Catalog / Inventory API를 정리한다.

공통 응답과 헤더 규칙은 [Common API Response](common.md)를 따른다.

## List Products

`GET /api/products?status={status}&q={query}`

### Query

| Name | Required | Description |
|---|---:|---|
| `status` | yes | 상품 판매 상태. 고객 앱은 `ON_SALE`을 사용한다. |
| `q` | no | 상품 코드 또는 상품명 부분 검색어. 공백은 제거되며 빈 값이면 필터를 적용하지 않는다. |

`status`는 현재 조회 API에서 필수 query parameter다. 고객 앱은 항상 `ON_SALE`을 전달한다. `q`는 대소문자를 구분하지 않고 `productCode`, `name`에 적용된다.

### Headers

| Header | Required | Description |
|---|---:|---|
| `X-Correlation-Id` | no | HTTP 흐름 추적 기준 |

### Response

`200 OK`

```json
{
  "success": true,
  "data": [
    {
      "productCode": "LIMITED-001",
      "name": "Limited Hoodie",
      "status": "ON_SALE",
      "listPrice": 12000.00
    }
  ],
  "trace": {
    "correlationId": "customer-app-catalog-list"
  }
}
```

## Get Product

`GET /api/products/{productCode}`

### Response Data

| Field | Description |
|---|---|
| `productCode` | 상품 코드 |
| `name` | 상품명 |
| `status` | `ON_SALE` or `STOPPED` |
| `listPrice` | 판매 가격 |

### Error Codes

| HTTP Status | Code | Case |
|---:|---|---|
| 404 | `CATALOG_PRODUCT_NOT_FOUND` | target product does not exist |
| 500 | `CATALOG_DATA_INTEGRITY_ERROR` | unexpected catalog data integrity failure |

## List Stocks

`GET /api/stocks?productCode={productCode}`

### Query

| Name | Required | Description |
|---|---:|---|
| `productCode` | no | 지정하면 해당 상품의 SKU 재고만 반환한다. 고객 앱은 선택 상품 코드로 조회한다. |

`productCode`를 생략하면 전체 SKU 재고 목록이 반환된다. 고객 화면에서는 선택 상품의 SKU만 표시하기 위해 항상 상품 코드를 전달한다.

### Response

`200 OK`

```json
{
  "success": true,
  "data": [
    {
      "skuId": "SKU-001",
      "productCode": "LIMITED-001",
      "availableQuantity": 10,
      "reservedQuantity": 2,
      "version": 3
    }
  ],
  "trace": {
    "correlationId": "customer-app-stock-list"
  }
}
```

## Get Stock

`GET /api/stocks/{skuId}`

### Response Data

| Field | Description |
|---|---|
| `skuId` | SKU ID |
| `productCode` | 연결 상품 코드 |
| `availableQuantity` | 주문 가능 수량 |
| `reservedQuantity` | 예약 중 수량 |
| `version` | 재고 row version |

### Error Codes

| HTTP Status | Code | Case |
|---:|---|---|
| 404 | `INVENTORY_STOCK_NOT_FOUND` | target SKU does not exist |

## Set Stock Quantity

`PUT /api/stocks/{skuId}`

이 endpoint는 고객 앱이 아니라 관리자 앱과 로컬 E2E seed에서 사용한다. SKU가 없으면 새 row를 만들고, 이미 있으면 `availableQuantity`를 갱신한다.

### Request

```json
{
  "productCode": "LIMITED-001",
  "availableQuantity": 20
}
```

### Fields

| Field | Required | Rule |
|---|---:|---|
| `productCode` | yes | non-blank |
| `availableQuantity` | yes | `>= 0` |

### Response

`200 OK`

```json
{
  "success": true,
  "data": {
    "skuId": "SKU-001",
    "productCode": "LIMITED-001",
    "availableQuantity": 20,
    "reservedQuantity": 0,
    "version": 1
  },
  "trace": {
    "correlationId": "admin-stock-set"
  }
}
```

### Error Codes

| HTTP Status | Code | Case |
|---:|---|---|
| 400 | `INVENTORY_INVALID_REQUEST` | request body is invalid |
| 404 | `INVENTORY_STOCK_NOT_FOUND` | target SKU does not exist for read endpoint |

## Client Flow

Customer App uses these APIs in this order:

1. `GET /api/products?status=ON_SALE`
2. User selects a product.
3. `GET /api/stocks?productCode={productCode}`
4. User selects a SKU and creates an order through [Customer Order API](customer-orders.md).

The app does not derive order price from Inventory. It uses Catalog `listPrice` as the `unitPrice` in the order request.
