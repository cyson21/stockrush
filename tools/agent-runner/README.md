# Agent Runner

Agent Runner creates AI Run Ledger folders for traceable agentic development work.

## Commands

Create a run:

```bash
./tools/agent-runner/agent-runner start \
  --slug order-saga \
  --task "Implement order saga" \
  --scope "services/order-service, services/inventory-service" \
  --completion "Order can complete and fail through Kafka events"
```

List runs:

```bash
./tools/agent-runner/agent-runner list
```

Record verification:

```bash
./tools/agent-runner/agent-runner verify 2026-05-12-1700-order-saga \
  --command "python -m unittest discover tools/dev-rag/tests" \
  --result "PASS" \
  --notes "3 tests"
```

