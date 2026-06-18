# Architecture Guard Rules

## Purpose

Architecture Guard prevents AI-assisted implementation from drifting away from the StockRush service boundaries and event rules.

This document owns rule definitions. Command usage and CLI behavior are documented in `tools/architecture-guard/README.md`.

## Rule Report Format

Each violation should be reported with:

```text
rule_id
severity
file
message
suggested_fix
```

Severity:

```text
error: must be fixed before merge
warning: should be fixed or documented
info: useful design feedback
```

## Rules

| Rule | Status |
|---|---|
| ARCH-001 Service Schema Ownership | Implemented |
| ARCH-002 Controller Response Boundary | Implemented |
| ARCH-003 Kafka Event Envelope | Implemented |
| ARCH-004 Outbox Structure | Implemented |
| ARCH-005 Consumer Idempotency | Planned |
| ARCH-006 Synchronous Call Allow List | Planned |
| ARCH-007 Correlation ID Propagation | Implemented |
| ARCH-008 Retry and DLQ Visibility | Planned |
| ARCH-009 Actuator Operations Exposure | Implemented |
| ARCH-010 Service Correlation MDC | Implemented |
| ARCH-011 Gateway Security Routes | Implemented |
| ARCH-012 Trusted Identity Headers | Implemented |
| ARCH-013 Demo Backend Port Exposure | Implemented |
| ARCH-014 Nginx Direct Backend Proxying | Implemented |
| ARCH-015 Customer Trusted Identity | Implemented |

### ARCH-001: Service Schema Ownership

Severity: error

Services must not access another service schema directly through SQL.

Kafka topic names, event names, payload names, and package names are not schema access.

Allowed:

```text
order-service -> orders schema
inventory-service -> inventory schema
payment-service -> payment schema
catalog-service -> catalog schema
```

Disallowed:

```text
order-service -> inventory schema table query
payment-service -> orders schema table update
```

Suggested fix:

```text
Use service API, Kafka event, or read model instead of direct schema access.
```

### ARCH-002: Controller Response Boundary

Severity: error

Controller responses must not expose JPA entities directly.

Disallowed:

```java
@GetMapping
public Product findProduct() {
    return productRepository.findById(id).orElseThrow();
}
```

Allowed:

```java
@GetMapping
public ProductResponse findProduct() {
    Product product = productService.findProduct(id);
    return ProductResponse.from(product);
}
```

### ARCH-003: Kafka Event Envelope

Severity: error

Kafka events must include:

- `eventId`
- `eventType`
- `eventVersion`
- `aggregateId`
- `correlationId`
- `idempotencyKey`
- `occurredAt`
- `payload`

Suggested fix:

```text
Wrap domain payloads in the common event envelope before publishing.
```

### ARCH-004: Outbox Events Structure

Severity: error

`outbox_events` rows used by the event relay must include:

- event id
- aggregate id
- event type
- event version
- payload
- status
- retry count
- error message
- created at
- published at

Suggested fix:

```text
Use the service outbox table instead of publishing Kafka events directly inside the transaction path.
```

### ARCH-005: Consumer Idempotency

Severity: error

Consumers must record processed event IDs before acknowledging successful processing.

Suggested fix:

```text
Add processed_event storage keyed by eventId or idempotencyKey.
```

### ARCH-006: Synchronous Call Allow List

Severity: warning

Synchronous service calls must be documented in an allow list.

Initial allowed calls:

```text
gateway -> auth-service
gateway -> catalog-service
gateway -> order-service
gateway -> admin read APIs
order-service -> catalog-service for product snapshot validation
```

Disallowed by default:

```text
inventory-service -> payment-service
payment-service -> inventory-service
promotion-service -> inventory-service
```

Suggested fix:

```text
Use Kafka events for cross-service workflow steps unless the call is in the allow list.
```

### ARCH-007: Correlation ID Propagation

Severity: error

HTTP requests and Kafka events in the same user action must share a correlation ID.

Gateway must resolve `X-Correlation-Id` at the HTTP boundary:

- use the incoming header when present
- create one when absent
- expose it to downstream proxy handlers through request headers
- store it in MDC under `correlationId` for request-scope logs
- return it in the response header

Suggested fix:

```text
Add a Gateway OncePerRequestFilter that resolves X-Correlation-Id, stores it in MDC, and wraps request headers before proxy forwarding.
```

### ARCH-008: Retry and DLQ Visibility

Severity: warning

Retry and dead letter handling must produce inspectable records for admin operations.

Suggested fix:

```text
Store failed event metadata and expose it through an admin endpoint or read model.
```

### ARCH-009: Actuator Operations Exposure

Severity: error

Every backend service and Gateway must include Spring Boot Actuator and expose the baseline operations endpoints:

- `health`
- `info`
- `metrics`

Suggested fix:

```text
Add spring-boot-starter-actuator and expose health, info, and metrics through service configuration.
```

### ARCH-010: Service Correlation MDC

Severity: error

HTTP API services must resolve `X-Correlation-Id` at their own request boundary, even when traffic does not enter through Gateway.

The filter must:

- use the incoming header when present
- create one when absent
- be a `src/main/java` Spring component filter
- run at `Ordered.HIGHEST_PRECEDENCE`
- expose it to controllers and exception handlers through request headers
- store it in MDC under `correlationId` for request-scope logs
- return it in the response header
- clear MDC in `finally`

Suggested fix:

```text
Add a service-local OncePerRequestFilter that resolves X-Correlation-Id, stores it in MDC, wraps request headers, and clears MDC in finally.
```

### ARCH-011: Gateway Security Routes

Severity: error

Gateway must apply OAuth2 Resource Server route authorization for customer and admin entry points.

At minimum, the following must be enforced in `SecurityConfig`:

- admin read APIs require `ROLE_ADMIN`
- customer order and read-model APIs require `ROLE_CUSTOMER`
- session creation policy is stateless

Suggested fix:

```text
Add SecurityConfig with oauth2ResourceServer JWT and requestMatchers covering admin and customer security routes.
```

### ARCH-012: Trusted Identity Headers

Severity: error

Gateway must remove client supplied identity headers and rewrite trusted headers from the authenticated JWT before forwarding to downstream services.

The trusted path must:

- remove existing identity headers before proxying
- set `X-StockRush-Subject` from JWT subject
- set `X-Operator-Id` from authenticated admin principal
- set `X-StockRush-Roles` and `X-StockRush-Email` from token claims

Suggested fix:

```text
Introduce a trusted identity header helper in Gateway and reuse it in all protected route proxies.
```

### ARCH-013: Demo Backend Port Exposure

Severity: error

Demo Docker Compose must not publish host ports for internal backend services.

Allowed host port exposure:

- Gateway
- Customer/Admin web apps
- Keycloak
- infrastructure required for local operation, such as PostgreSQL, Redis, Kafka, and Kafka UI

Disallowed:

```yaml
services:
  order-service:
    ports:
      - "28083:18083"
```

Suggested fix:

```text
Remove `ports:` from internal backend service blocks and route demo traffic through Gateway and web apps.
```

### ARCH-014: Nginx Direct Backend Proxying

Severity: error

The demo web Nginx config must not expose legacy service prefixes such as `/orders/`, `/inventory/`, `/payment/`, `/catalog/`, or `/promotion/` by proxying them directly to backend service names.

Allowed:

```nginx
location /api/orders {
  proxy_pass http://gateway:18080;
}
```

Disallowed:

```nginx
location /orders/ {
  proxy_pass http://order-service:18083;
}
```

Suggested fix:

```text
Proxy public and smoke traffic through Gateway `/api/**` paths only.
```

### ARCH-015: Customer Trusted Identity

Severity: error

Customer order routes must derive customer identity from the trusted `X-StockRush-Subject` header and reject missing trusted identity by default.

Disallowed:

- falling back from missing `X-StockRush-Subject` to request body `memberId`
- falling back from missing `X-StockRush-Subject` to query `memberId`
- returning customer order detail without owner-scoped lookup

Allowed:

```java
TrustedCustomerIdentity.require(authenticatedMemberId)
```

Suggested fix:

```text
Require trusted subject identity on customer routes and keep memberId filters only on protected admin routes.
```
