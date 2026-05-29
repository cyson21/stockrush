#!/usr/bin/env python3
"""MCP server for StockRush project context and local guard tools."""
# MCP를 통해 프로젝트 컨텍스트 조회와 도구 실행을 위한 서버 경계를 정의합니다.
from __future__ import annotations

import argparse
import asyncio
import importlib.util
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


try:
    from mcp.server import Server
    from mcp.server.stdio import stdio_server
    from mcp.types import Resource, TextContent, Tool
except ImportError:
    @dataclass
    class Resource:  # type: ignore[override]
        uri: str
        name: str
        description: str | None = None
        mimeType: str | None = None

    @dataclass
    class TextContent:  # type: ignore[override]
        type: str
        text: str

    @dataclass
    class Tool:  # type: ignore[override]
        name: str
        description: str
        inputSchema: dict[str, Any]

    class Server:  # type: ignore[override]
        def __init__(self, _name: str) -> None:
            self._name = _name

        def list_resources(self):
            def decorator(fn):
                return fn
            return decorator

        def read_resource(self):
            def decorator(fn):
                return fn
            return decorator

        def list_tools(self):
            def decorator(fn):
                return fn
            return decorator

        def call_tool(self):
            def decorator(fn):
                return fn
            return decorator

    async def stdio_server(_server: Server) -> None:  # type: ignore[override]
        raise RuntimeError("MCP SDK is not installed.")


def load_module(module_name: str, path: Path) -> Any:
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load module: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


def dev_rag_module(root: Path) -> Any:
    return load_module("stockrush_dev_rag", root / "tools" / "dev-rag" / "dev_rag.py")


def architecture_guard_module(root: Path) -> Any:
    return load_module(
        "stockrush_architecture_guard",
        root / "tools" / "architecture-guard" / "architecture_guard.py",
    )


def safe_project_path(root: Path, path_text: str) -> Path:
    candidate = (root / path_text).resolve()
    try:
        candidate.relative_to(root.resolve())
    except ValueError as exc:
        raise ValueError(f"Path is outside project root: {path_text}") from exc
    return candidate


def list_project_docs(root: Path) -> list[dict[str, str]]:
    docs: list[dict[str, str]] = []
    candidates = [root / "README.md", root / "TODO.md", root / "docs"]
    for candidate in candidates:
        if candidate.is_file():
            docs.append({"path": str(candidate.relative_to(root)), "type": "markdown"})
        elif candidate.is_dir():
            for path in sorted(candidate.rglob("*.md")):
                docs.append({"path": str(path.relative_to(root)), "type": "markdown"})
    return docs


def read_project_doc(root: Path, path_text: str) -> str:
    path = safe_project_path(root, path_text)
    if not path.exists() or not path.is_file():
        raise FileNotFoundError(f"Document not found: {path_text}")
    if path.suffix != ".md":
        raise ValueError(f"Only markdown documents can be read: {path_text}")
    return path.read_text(encoding="utf-8")


def run_dev_rag_ingest(root: Path) -> dict[str, Any]:
    module = dev_rag_module(root)
    index_path = root / ".dev-rag" / "index.sqlite3"
    count = module.ingest(index_path, root, module.default_sources(root))
    return {"indexed_files": count, "index_path": ".dev-rag/index.sqlite3"}


def run_dev_rag_search(root: Path, query: str, limit: int) -> str:
    module = dev_rag_module(root)
    index_path = root / ".dev-rag" / "index.sqlite3"
    results = module.search(index_path, query, limit)
    return module.format_search_results(results)


def run_dev_rag_context(root: Path, goal: str, limit: int) -> str:
    module = dev_rag_module(root)
    index_path = root / ".dev-rag" / "index.sqlite3"
    results = module.search(index_path, goal, limit)
    return module.generate_context(goal, results)


def run_architecture_guard(root: Path) -> dict[str, Any]:
    module = architecture_guard_module(root)
    violations = module.check(root)
    return {
        "passed": len(violations) == 0,
        "violations": [module.asdict(violation) for violation in violations],
    }


def build_resource_list() -> list[Resource]:
    return [
        Resource(
            uri="stockrush://readme",
            name="StockRush README",
            description="Project overview and primary links",
            mimeType="text/markdown",
        ),
        Resource(
            uri="stockrush://todo",
            name="StockRush TODO",
            description="Current implementation tracker",
            mimeType="text/markdown",
        ),
        Resource(
            uri="stockrush://tracking",
            name="StockRush Project Status",
            description="Current status, document ownership, and work log",
            mimeType="text/markdown",
        ),
        Resource(
            uri="stockrush://adr-index",
            name="StockRush ADR Index",
            description="List of architecture decision records",
            mimeType="application/json",
        ),
    ]


def build_tool_list() -> list[Tool]:
    return [
        Tool(
            name="list_project_docs",
            description="List markdown documents available in the StockRush repository.",
            inputSchema={"type": "object", "properties": {}},
        ),
        Tool(
            name="read_project_doc",
            description="Read a markdown document by project-relative path.",
            inputSchema={
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Project-relative markdown path"},
                },
                "required": ["path"],
            },
        ),
        Tool(
            name="dev_rag_ingest",
            description="Index project documents into the Dev RAG SQLite store.",
            inputSchema={"type": "object", "properties": {}},
        ),
        Tool(
            name="dev_rag_search",
            description="Search Dev RAG indexed chunks.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {"type": "string"},
                    "limit": {"type": "integer", "default": 8},
                },
                "required": ["query"],
            },
        ),
        Tool(
            name="dev_rag_context",
            description="Generate a Markdown Context Pack for a work goal.",
            inputSchema={
                "type": "object",
                "properties": {
                    "goal": {"type": "string"},
                    "limit": {"type": "integer", "default": 8},
                },
                "required": ["goal"],
            },
        ),
        Tool(
            name="architecture_guard_check",
            description="Run StockRush architecture guard checks.",
            inputSchema={"type": "object", "properties": {}},
        ),
    ]


async def handle_read_resource(root: Path, uri: str) -> list[TextContent]:
    if uri == "stockrush://readme":
        return [TextContent(type="text", text=read_project_doc(root, "README.md"))]
    if uri == "stockrush://todo":
        return [TextContent(type="text", text=read_project_doc(root, "TODO.md"))]
    if uri == "stockrush://tracking":
        return [TextContent(type="text", text=read_project_doc(root, "docs/project-tracking.md"))]
    if uri == "stockrush://adr-index":
        adrs = [
            doc for doc in list_project_docs(root)
            if doc["path"].startswith("docs/adr/")
        ]
        return [TextContent(type="text", text=json.dumps(adrs, ensure_ascii=False, indent=2))]
    return [TextContent(type="text", text=json.dumps({"error": f"Unknown resource URI: {uri}"}))]


async def handle_call_tool(root: Path, name: str, arguments: dict[str, Any]) -> list[TextContent]:
    if name == "list_project_docs":
        return [TextContent(type="text", text=json.dumps(list_project_docs(root), ensure_ascii=False, indent=2))]
    if name == "read_project_doc":
        return [TextContent(type="text", text=read_project_doc(root, arguments["path"]))]
    if name == "dev_rag_ingest":
        return [TextContent(type="text", text=json.dumps(run_dev_rag_ingest(root), ensure_ascii=False, indent=2))]
    if name == "dev_rag_search":
        return [TextContent(type="text", text=run_dev_rag_search(root, arguments["query"], int(arguments.get("limit", 8))))]
    if name == "dev_rag_context":
        return [TextContent(type="text", text=run_dev_rag_context(root, arguments["goal"], int(arguments.get("limit", 8))))]
    if name == "architecture_guard_check":
        return [TextContent(type="text", text=json.dumps(run_architecture_guard(root), ensure_ascii=False, indent=2))]
    return [TextContent(type="text", text=json.dumps({"error": f"Unknown tool: {name}"}))]


def make_server(root: Path) -> Server:
    server = Server("stockrush-project")

    @server.list_resources()
    async def list_resources() -> list[Resource]:
        return build_resource_list()

    @server.read_resource()
    async def read_resource(uri: str) -> list[TextContent]:
        return await handle_read_resource(root, uri)

    @server.list_tools()
    async def list_tools() -> list[Tool]:
        return build_tool_list()

    @server.call_tool()
    async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
        return await handle_call_tool(root, name, arguments)

    return server


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="StockRush Project MCP Server")
    parser.add_argument("--root", default=".", help="Project root path")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)
    root = Path(args.root).expanduser().resolve()
    server = make_server(root)
    asyncio.run(stdio_server(server))


if __name__ == "__main__":
    main()
