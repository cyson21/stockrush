# Architecture Guard

Architecture Guard checks whether StockRush implementation follows core architecture rules.

The first version scans Java and SQL files without external dependencies.

## Command

```bash
./tools/architecture-guard/architecture-guard check
```

JSON output:

```bash
./tools/architecture-guard/architecture-guard check --format json
```

## Rules

- `ARCH-001`: service schema ownership
- `ARCH-002`: controller must not return JPA entities directly
- `ARCH-003`: Kafka event envelope fields must exist
- `ARCH-004`: `outbox_events` table must include required columns
- `ARCH-007`: Gateway must resolve and propagate `X-Correlation-Id`
- `ARCH-009`: backend services and Gateway must expose Actuator health, info, and metrics
- `ARCH-010`: HTTP API services must keep `X-Correlation-Id` in request-scope MDC

Additional rules and planned checks are defined in `docs/architecture/architecture-guard-rules.md`.

## Exit Codes

- `0`: no violations
- `1`: one or more violations
