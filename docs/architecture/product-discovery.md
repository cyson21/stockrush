# Product Discovery

StockRush product discovery currently stays in Catalog Service. This is intentional: product code, product name, sales status, and list price are Catalog-owned data, and the current customer search requirement is simple keyword filtering over that owned data.

## Current Implementation

Customer clients use:

```text
GET /api/products?status=ON_SALE&q={query}
```

Rules:

- `status` is required.
- Customer clients use `ON_SALE`.
- `q` is optional.
- Blank `q` is ignored.
- Search is case-insensitive.
- Search applies to `productCode` and `name`.
- SQL wildcard characters in `q` are treated as literal text.

Customer App sends the query only after trimming. If the user enters whitespace only, it falls back to the same full `ON_SALE` product list flow.

## Why Not a Product Search Projection Yet

The current Read Model Service owns order lifecycle projections, not product discovery. A separate product search projection would be useful only when Catalog's direct query model stops matching the product discovery problem.

Do not add a separate product search projection only to make the architecture look larger. It would add Kafka topics, projection lag, rebuild logic, and consistency questions without improving the current demo flow.

## Triggers For a Later Projection

Add a Product Search Read Model when at least one of these becomes true:

- search needs product + stock + promotion data in one read-optimized response
- faceted search is required, such as size, category, price range, stock availability, or coupon eligibility
- product list traffic becomes high enough to isolate read scaling from Catalog writes
- search relevance requires a specialized engine or denormalized ranking fields
- external clients need eventual-consistent discovery data without depending on Catalog's transactional schema

## Later Projection Shape

A later slice can add:

- topic: `stockrush.catalog.events.v1`
- events: `ProductCreated`, `ProductUpdated`, `ProductSalesStatusChanged`
- table: `read_model.product_search_documents`
- API: `GET /api/read-model/products?status={status}&q={query}&facet...`
- rebuild command: backfill all Catalog products into the projection

Until those triggers exist, Catalog Service remains the source for product discovery and Read Model Service stays focused on order summaries.

## Verification

Current verification is covered by:

- Catalog Service integration tests for product code/name search, trimming, case-insensitive matching, blank query fallback, and literal wildcard handling.
- Customer App tests for query requests and whitespace fallback.
- API docs in `docs/api/catalog-inventory.md`.
