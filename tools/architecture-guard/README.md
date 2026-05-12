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

## Initial Rules

- `ARCH-001`: service schema ownership
- `ARCH-002`: controller must not return JPA entities directly
- `ARCH-003`: Kafka event envelope fields must exist
- `ARCH-004`: Outbox table must include required columns

Additional planned rules are defined in `docs/architecture/architecture-guard-rules.md`.

## Exit Codes

- `0`: no violations
- `1`: one or more violations
