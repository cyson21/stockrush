# Development Operations Architecture

## Purpose

StockRush uses AI-assisted engineering as a traceable development process, not as an unstructured code generation shortcut.

The development operations architecture makes sure every AI-assisted task has:

- a clear goal
- retrieved context
- scoped file ownership
- recorded decisions
- verification evidence
- updated project knowledge

## Components

### Dev RAG

Dev RAG generates task-specific Context Packs from project knowledge.

Sources include:

- project rules
- `agent-rules` references
- ADRs
- architecture documents
- API documents
- schema documents
- source summaries
- troubleshooting records

The initial implementation starts with keyword search and SQLite-backed document metadata. Vector search can be added after the document structure and query patterns stabilize.

### Project MCP Server

Project MCP Server exposes project operations to AI agents through explicit tools.

Expected tool categories:

- document lookup
- ADR lookup
- Dev RAG context generation
- Docker status lookup
- service port lookup
- Kafka topic summary
- database schema summary
- verification command discovery

The MCP server does not hide destructive actions behind generic commands. Any operation that changes remote state, deletes data, or publishes externally remains an explicit approval point.

Current server path:

```text
tools/project-mcp/project_mcp_server.py
```

Current resources:

- `stockrush://readme`
- `stockrush://todo`
- `stockrush://tracking`
- `stockrush://adr-index`

Current tools:

- `list_project_docs`
- `read_project_doc`
- `dev_rag_ingest`
- `dev_rag_search`
- `dev_rag_context`
- `architecture_guard_check`

### Agentic Workflow Runner

Agentic Workflow Runner coordinates role-based work.

Initial roles:

- Planner Agent
- Context Agent
- Backend Implementer Agent
- Frontend Implementer Agent
- Reviewer Agent
- Test Agent
- Documentation Agent

The first version can be a local CLI that writes run records and prompts. LangGraph or OpenAI Agents SDK can be introduced later if durable execution, handoff state, or integrated tracing becomes necessary.

Current runner path:

```text
tools/agent-runner/
```

Current commands:

```bash
./tools/agent-runner/agent-runner start
./tools/agent-runner/agent-runner list
./tools/agent-runner/agent-runner verify
```

### AI Run Ledger

AI Run Ledger records how each AI-assisted task was performed.

Each run stores:

- goal
- context pack
- agent plan
- decisions
- changed files
- verification result

This record lets the portfolio show how AI was used as part of an engineering workflow.

### Architecture Guard

Architecture Guard checks whether implementation follows the project boundaries.

Initial rule categories:

- service schema ownership
- controller response shape
- Kafka event envelope
- Outbox structure
- Consumer idempotency
- synchronous call allow list

## Workflow

```text
Define work goal
 -> Generate Context Pack
 -> Create agent plan
 -> Implement scoped change
 -> Run verification
 -> Record changed files and evidence
 -> Re-ingest new docs and summaries
```

## Boundaries

- AI tools do not replace verification.
- Generated code must pass project tests and architecture guard checks.
- Scope changes are recorded before implementation continues.
- Human approval is required for destructive actions, external account setup, remote publication, and credential handling.
- AI Run Ledger records are evidence, not a substitute for tests.

## Portfolio Value

This architecture supports the following portfolio claim:

> I built the project with a traceable agentic development workflow: RAG-based context retrieval, project-specific MCP tools, run ledgers, and architecture guards were used to keep AI-generated work aligned with system design and verification criteria.
