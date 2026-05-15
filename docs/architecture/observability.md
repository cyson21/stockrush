# Observability Baseline

StockRush observability is split into a completed baseline and later telemetry expansion. The completed slice focuses on traceability and local operational checks that can be verified without external cloud resources.

## Completed Baseline

### Correlation ID

HTTP requests use `X-Correlation-Id` as the cross-service trace key.

- Gateway resolves the header at the edge.
- If the header is absent, Gateway creates one.
- Gateway returns the resolved value in the response header.
- Gateway stores the value in request-scope MDC under `correlationId`.
- Gateway forwards the same header to upstream services.
- Each HTTP API service also resolves the header at its own boundary, stores it in MDC, returns it in the response, and clears MDC in `finally`.

Kafka events carry the same flow identifier as `correlationId` inside the event envelope. Local E2E runners send explicit correlation IDs for order, coupon, outbox, burst, and recovery scenarios so the HTTP request, domain event, outbox row, and admin audit row can be tied together during debugging.

Current automated guards:

- `ARCH-007`: Gateway must resolve and propagate `X-Correlation-Id`.
- `ARCH-010`: HTTP API services must keep `X-Correlation-Id` in request-scope MDC.
- Service integration tests assert header echo and MDC behavior.

### Actuator Operations Endpoints

Every backend service and Gateway exposes the baseline Spring Boot Actuator endpoints:

- `/actuator/health`
- `/actuator/info`
- `/actuator/metrics`

Current automated guards:

- `ARCH-009`: backend services and Gateway must include Actuator and expose `health`, `info`, and `metrics`.
- `demo-smoke` checks all three endpoints for Gateway and the backend services.

## Metrics To Watch

The current demo stack exposes raw Actuator metrics. A later Prometheus/Grafana slice should turn these into dashboards and alerts.

Core business metrics:

- order creation count by result
- order confirmation rate
- order cancellation rate by reason
- payment failure rate by payment method
- delayed payment count and age
- coupon quote success/failure count
- coupon usage state count by `RESERVED`, `CONSUMED`, `RELEASED`

Reliability metrics:

- outbox pending count by service and event type
- outbox failed count by service and event type
- outbox retry count
- outbox publish latency
- consumer processed count by topic and event type
- duplicate event skip count
- Kafka consumer lag by consumer group and topic
- Kafka outage recovery duration

Runtime metrics:

- HTTP request count and latency by service, method, and path group
- HTTP 4xx/5xx count by service
- JVM memory usage
- JVM thread count
- datasource active connection count

## OpenTelemetry Scope

OpenTelemetry is not part of the completed slice. The planned scope is:

1. Gateway, Order, Inventory, and Payment first.
2. Add distributed traces for order creation, inventory reservation, payment authorization, and order finalization.
3. Propagate W3C trace context in HTTP calls while preserving `X-Correlation-Id` as the business/debug key.
4. Add Kafka producer/consumer spans after the HTTP path is stable.
5. Expand to Promotion, Fulfillment, and Read Model only after the core order path has useful traces.

This keeps the portfolio claim precise: the current project has correlation-based traceability and Actuator-based local operation checks; full OpenTelemetry tracing and Prometheus/Grafana dashboards are a documented next slice.

## Verification Commands

```bash
./tools/architecture-guard/architecture-guard check
./scripts/demo-smoke.sh
```

For a running demo stack, the smoke verifies `health`, `info`, and `metrics` endpoint reachability. Architecture Guard verifies the static service requirements that keep future services from silently dropping the baseline.
