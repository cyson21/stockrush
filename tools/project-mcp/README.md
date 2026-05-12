# Project MCP Server

Project MCP Server exposes StockRush project context and local verification tools to AI agents.

## Server

```bash
python tools/project-mcp/project_mcp_server.py --root .
```

## Resources

- `stockrush://readme`
- `stockrush://todo`
- `stockrush://tracking`
- `stockrush://adr-index`

## Tools

- `list_project_docs`
- `read_project_doc`
- `dev_rag_ingest`
- `dev_rag_search`
- `dev_rag_context`
- `architecture_guard_check`

## Purpose

The MCP server is a controlled interface for project-aware agents.

It gives agents explicit ways to retrieve context and run local checks without relying on broad shell access.

