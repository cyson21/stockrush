# Common API Response

## Header Rules

| Header | Direction | Required | Purpose |
|---|---|---:|---|
| `X-Correlation-Id` | request/response | no | Trace one user action across HTTP and Kafka |
| `Idempotency-Key` | command request | yes | Prevent duplicate command processing |

If `X-Correlation-Id` is absent, the receiving service creates one and returns it in the response.

## Success Response

```json
{
  "success": true,
  "data": {},
  "trace": {
    "correlationId": "018f8d0b-8d32-7c42-9f1b-78328e0f7a11"
  }
}
```

## Error Response

```json
{
  "success": false,
  "error": {
    "code": "ORDER_INVALID_STATUS",
    "message": "Order status cannot be changed.",
    "details": {}
  },
  "trace": {
    "correlationId": "018f8d0b-8d32-7c42-9f1b-78328e0f7a11"
  }
}
```

## Error Code Prefixes

| Prefix | Owner |
|---|---|
| `COMMON_` | shared HTTP/request validation |
| `CATALOG_` | catalog-service |
| `INVENTORY_` | inventory-service |
| `ORDER_` | order-service |
| `PAYMENT_` | payment-service |
| `PROMOTION_` | promotion-service |

## HTTP Status Guide

| Status | Use |
|---:|---|
| 200 | successful query or idempotent command replay |
| 201 | command created a new aggregate |
| 202 | command accepted and async processing started |
| 400 | invalid request body or parameter |
| 404 | resource not found |
| 409 | duplicate or invalid state transition |
| 422 | business rule failure |
| 500 | unexpected server failure |
