# Dev RAG

Dev RAG is a local development-operations retrieval tool for StockRush.

It indexes project documents, searches relevant chunks, and generates Markdown Context Packs for AI-assisted implementation work.

## Commands

```bash
./tools/dev-rag/dev-rag ingest
./tools/dev-rag/dev-rag search "order saga retry dlq"
./tools/dev-rag/dev-rag context "Inventory reservation 동시성 구현"
```

## Storage

The default index path is:

```text
.dev-rag/index.sqlite3
```

This file is generated runtime data and is excluded from Git.

## Default Sources

The tool indexes existing files from:

- `AGENTS.md`
- `README.md`
- `docs`
- selected local `agent-rules` references when they exist on the machine

Missing optional sources are skipped.

## Context Pack

Context output includes:

- goal
- applied rules
- relevant decisions
- relevant architecture
- relevant files
- implementation cautions
- verification commands
- sources

