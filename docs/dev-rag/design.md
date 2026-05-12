# Dev RAG Design

## Purpose

Dev RAG generates task-specific Context Packs from StockRush project knowledge.

The goal is to help AI agents and humans start each task with the right rules, decisions, architecture context, file references, and verification commands.

Dev RAG is for development operations. It is not a customer-facing product feature.

## Sources

Initial sources:

- `AGENTS.md`
- `README.md`
- `docs/adr`
- `docs/architecture`
- `docs/api`
- `docs/operations`
- `docs/dev-rag`
- service source summaries after implementation begins

External local references:

- `/Users/chanyang.son/agent-rules/sources/AGENTS_BASE.md`
- `/Users/chanyang.son/agent-rules/sources/AGENTS_OPERATIONS.md`
- `/Users/chanyang.son/agent-rules/sources/projects/java-backend-rule-chain.md`
- relevant managed skills under `/Users/chanyang.son/agent-rules/sources/skills`

## Initial Storage

The first implementation uses SQLite.

Proposed tables:

```text
documents
- id
- path
- source_type
- title
- content_hash
- indexed_at

chunks
- id
- document_id
- heading_path
- chunk_text
- chunk_order
- token_estimate

query_logs
- id
- query_text
- created_at
- selected_chunk_ids
```

Vector search can be added later with pgvector after query patterns are stable.

## Search Strategy

Phase 0 search is keyword-first.

Ranking signals:

- exact term match
- heading match
- source type priority
- recent ADR priority
- file path relevance

Source priority:

```text
AGENTS and hard rules
 -> ADR
 -> architecture docs
 -> API/schema docs
 -> source summaries
 -> run history
```

## CLI Commands

Planned commands:

```bash
./tools/dev-rag/dev-rag ingest
./tools/dev-rag/dev-rag search "order saga retry dlq"
./tools/dev-rag/dev-rag context "Inventory reservation 동시성 구현"
```

Command behavior:

- `ingest`: scan configured sources, chunk markdown and code summaries, update SQLite metadata
- `search`: return ranked chunks with source paths
- `context`: produce a Markdown Context Pack for a specific task goal

## Context Pack Format

```markdown
# Context Pack

## Goal

## Applied Rules

## Relevant Decisions

## Relevant Architecture

## Relevant Files

## Implementation Cautions

## Verification Commands

## Sources
```

## Scope Control

Dev RAG must not include secrets, local credentials, or environment files.

Excluded paths:

```text
.env
.env.*
node_modules
target
build
dist
coverage
.git
data
tmp
```

## Acceptance Criteria

The first usable version is complete when:

- it can search indexed chunks by keyword
- it can generate a Context Pack markdown file for a task goal
- generated Context Packs include source paths
- excluded paths are skipped

