#!/usr/bin/env python3
"""Development-operations retrieval tool for StockRush."""
# 문서/마크업을 인덱싱해 검색 맥락을 만드는 경량 검색 도구입니다.
from __future__ import annotations

import argparse
import hashlib
import re
import sqlite3
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Sequence


DEFAULT_INDEX = Path(".dev-rag/index.sqlite3")
EXCLUDED_DIRS = {
    ".git",
    ".worktrees",
    ".dev-rag",
    "node_modules",
    "target",
    "build",
    "dist",
    "coverage",
    "data",
    "tmp",
}
EXCLUDED_FILES = {".env"}
INDEX_EXTENSIONS = {".md", ".txt", ".yaml", ".yml", ".json", ".java", ".ts", ".tsx", ".js", ".jsx", ".sql"}


@dataclass(frozen=True)
class SourceFile:
    path: Path
    source_type: str


@dataclass(frozen=True)
class SearchResult:
    path: str
    source_type: str
    heading_path: str
    chunk_text: str
    score: int


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def content_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def open_db(index_path: Path) -> sqlite3.Connection:
    index_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(index_path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS documents (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            path TEXT NOT NULL UNIQUE,
            source_type TEXT NOT NULL,
            title TEXT NOT NULL,
            content_hash TEXT NOT NULL,
            indexed_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS chunks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            document_id INTEGER NOT NULL,
            heading_path TEXT NOT NULL,
            chunk_text TEXT NOT NULL,
            chunk_order INTEGER NOT NULL,
            token_estimate INTEGER NOT NULL,
            FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS query_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            query_text TEXT NOT NULL,
            created_at TEXT NOT NULL,
            selected_chunk_ids TEXT NOT NULL
        );
        """
    )
    conn.commit()


def default_sources(root: Path) -> list[SourceFile]:
    agent_rules_root = Path.home() / "agent-rules"
    candidates = [
        SourceFile(root / "AGENTS.md", "project-rule"),
        SourceFile(root / "TODO.md", "project-task"),
        SourceFile(root / "README.md", "project-overview"),
        SourceFile(root / "docs", "project-doc"),
        SourceFile(root / ".ai-runs", "ai-run-ledger"),
        SourceFile(agent_rules_root / "sources" / "AGENTS_BASE.md", "agent-rule"),
        SourceFile(agent_rules_root / "sources" / "AGENTS_OPERATIONS.md", "agent-rule"),
        SourceFile(agent_rules_root / "sources" / "projects" / "java-backend-rule-chain.md", "agent-rule"),
    ]
    return [source for source in candidates if source.path.exists()]


def is_excluded(path: Path, root: Path) -> bool:
    if path.name in EXCLUDED_FILES:
        return True
    if path.name.startswith(".env"):
        return True
    try:
        parts = path.resolve().relative_to(root.resolve()).parts
    except ValueError:
        parts = path.parts
    return any(part in EXCLUDED_DIRS for part in parts)


def iter_files(source: SourceFile, root: Path) -> Iterable[SourceFile]:
    if source.path.is_file():
        if not is_excluded(source.path, root) and source.path.suffix in INDEX_EXTENSIONS:
            yield source
        return

    for path in sorted(source.path.rglob("*")):
        if path.is_file() and not is_excluded(path, root) and path.suffix in INDEX_EXTENSIONS:
            yield SourceFile(path, source.source_type)


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def document_title(path: Path, text: str) -> str:
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("#"):
            return stripped.lstrip("#").strip() or path.name
    return path.name


def split_markdown_chunks(text: str, max_chars: int = 1800) -> list[tuple[str, str]]:
    chunks: list[tuple[str, str]] = []
    heading_stack: list[str] = []
    current_heading = "Document"
    current_lines: list[str] = []

    def flush() -> None:
        nonlocal current_lines
        body = "\n".join(current_lines).strip()
        if body:
            chunks.extend(split_large_chunk(current_heading, body, max_chars))
        current_lines = []

    for line in text.splitlines():
        heading_match = re.match(r"^(#{1,6})\s+(.+?)\s*$", line)
        if heading_match:
            flush()
            level = len(heading_match.group(1))
            title = heading_match.group(2).strip()
            heading_stack[:] = heading_stack[: level - 1]
            heading_stack.append(title)
            current_heading = " > ".join(heading_stack)
            current_lines.append(line)
        else:
            current_lines.append(line)

    flush()
    if not chunks and text.strip():
        chunks.extend(split_large_chunk("Document", text.strip(), max_chars))
    return chunks


def split_large_chunk(heading: str, text: str, max_chars: int) -> list[tuple[str, str]]:
    if len(text) <= max_chars:
        return [(heading, text)]

    paragraphs = [paragraph.strip() for paragraph in re.split(r"\n\s*\n", text) if paragraph.strip()]
    chunks: list[tuple[str, str]] = []
    current: list[str] = []
    current_size = 0

    for paragraph in paragraphs:
        paragraph_size = len(paragraph)
        if current and current_size + paragraph_size + 2 > max_chars:
            chunks.append((heading, "\n\n".join(current)))
            current = []
            current_size = 0
        current.append(paragraph)
        current_size += paragraph_size + 2

    if current:
        chunks.append((heading, "\n\n".join(current)))
    return chunks


def token_estimate(text: str) -> int:
    return max(1, len(text) // 4)


def upsert_document(conn: sqlite3.Connection, source: SourceFile, root: Path) -> int:
    text = read_text(source.path)
    path_text = display_path(source.path, root)
    digest = content_hash(text)
    title = document_title(source.path, text)
    indexed_at = utc_now()

    existing = conn.execute("SELECT id, content_hash FROM documents WHERE path = ?", (path_text,)).fetchone()
    if existing and existing["content_hash"] == digest:
        return int(existing["id"])

    if existing:
        document_id = int(existing["id"])
        conn.execute(
            """
            UPDATE documents
            SET source_type = ?, title = ?, content_hash = ?, indexed_at = ?
            WHERE id = ?
            """,
            (source.source_type, title, digest, indexed_at, document_id),
        )
        conn.execute("DELETE FROM chunks WHERE document_id = ?", (document_id,))
    else:
        cursor = conn.execute(
            """
            INSERT INTO documents(path, source_type, title, content_hash, indexed_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (path_text, source.source_type, title, digest, indexed_at),
        )
        document_id = int(cursor.lastrowid)

    chunks = split_markdown_chunks(text)
    conn.executemany(
        """
        INSERT INTO chunks(document_id, heading_path, chunk_text, chunk_order, token_estimate)
        VALUES (?, ?, ?, ?, ?)
        """,
        [
            (document_id, heading, chunk, order, token_estimate(chunk))
            for order, (heading, chunk) in enumerate(chunks)
        ],
    )
    return document_id


def display_path(path: Path, root: Path) -> str:
    resolved = path.resolve()
    try:
        return str(resolved.relative_to(root.resolve()))
    except ValueError:
        return str(resolved)


def ingest(index_path: Path, root: Path, sources: Sequence[SourceFile]) -> int:
    with open_db(index_path) as conn:
        init_db(conn)
        count = 0
        seen_paths: set[str] = set()
        for source in sources:
            for source_file in iter_files(source, root):
                seen_paths.add(display_path(source_file.path, root))
                upsert_document(conn, source_file, root)
                count += 1
        if seen_paths:
            placeholders = ",".join("?" for _ in seen_paths)
            conn.execute(
                f"DELETE FROM documents WHERE path NOT IN ({placeholders})",
                tuple(sorted(seen_paths)),
            )
        conn.commit()
        return count


def query_terms(query: str) -> list[str]:
    return [term.lower() for term in re.findall(r"[\w가-힣.-]+", query) if len(term) > 1]


def score_chunk(row: sqlite3.Row, terms: Sequence[str]) -> int:
    haystack = f"{row['path']} {row['source_type']} {row['heading_path']} {row['chunk_text']}".lower()
    score = 0
    for term in terms:
        score += haystack.count(term)
        if term in str(row["heading_path"]).lower():
            score += 3
        if term in str(row["path"]).lower():
            score += 2
    if score == 0:
        return 0
    source_type = str(row["source_type"])
    if source_type in {"project-rule", "agent-rule"}:
        score += 2
    if source_type == "project-doc":
        score += 1
    return score


def search(index_path: Path, query: str, limit: int) -> list[SearchResult]:
    terms = query_terms(query)
    if not terms:
        return []

    with open_db(index_path) as conn:
        init_db(conn)
        rows = conn.execute(
            """
            SELECT d.path, d.source_type, c.heading_path, c.chunk_text, c.id
            FROM chunks c
            JOIN documents d ON d.id = c.document_id
            """
        ).fetchall()
        scored = []
        selected_ids = []
        for row in rows:
            score = score_chunk(row, terms)
            if score > 0:
                scored.append(
                    SearchResult(
                        path=str(row["path"]),
                        source_type=str(row["source_type"]),
                        heading_path=str(row["heading_path"]),
                        chunk_text=str(row["chunk_text"]),
                        score=score,
                    )
                )
                selected_ids.append(str(row["id"]))
        scored.sort(key=lambda item: (-item.score, item.path, item.heading_path))
        results = scored[:limit]
        conn.execute(
            "INSERT INTO query_logs(query_text, created_at, selected_chunk_ids) VALUES (?, ?, ?)",
            (query, utc_now(), ",".join(selected_ids[:limit])),
        )
        conn.commit()
        return results


def format_search_results(results: Sequence[SearchResult]) -> str:
    if not results:
        return "No results."

    blocks = []
    for index, result in enumerate(results, start=1):
        excerpt = compact_excerpt(result.chunk_text)
        blocks.append(
            "\n".join(
                [
                    f"{index}. {result.path}",
                    f"   source: {result.source_type}",
                    f"   heading: {result.heading_path}",
                    f"   score: {result.score}",
                    f"   excerpt: {excerpt}",
                ]
            )
        )
    return "\n\n".join(blocks)


def compact_excerpt(text: str, max_chars: int = 260) -> str:
    one_line = re.sub(r"\s+", " ", text).strip()
    if len(one_line) <= max_chars:
        return one_line
    return one_line[: max_chars - 3].rstrip() + "..."


def generate_context(goal: str, results: Sequence[SearchResult]) -> str:
    applied_rules = [item for item in results if item.source_type in {"project-rule", "agent-rule"}]
    decisions = [item for item in results if "/adr/" in item.path or item.path.startswith("docs/adr/")]
    architecture = [item for item in results if "/architecture/" in item.path or item.path.startswith("docs/architecture/")]
    files = list(results)

    return "\n".join(
        [
            "# Context Pack",
            "",
            "## Goal",
            "",
            goal,
            "",
            "## Applied Rules",
            "",
            format_context_list(applied_rules),
            "",
            "## Relevant Decisions",
            "",
            format_context_list(decisions),
            "",
            "## Relevant Architecture",
            "",
            format_context_list(architecture),
            "",
            "## Relevant Files",
            "",
            format_context_list(files),
            "",
            "## Implementation Cautions",
            "",
            "- Keep changes scoped to the current task.",
            "- Update ADRs or architecture docs before changing a documented decision.",
            "- Record verification evidence in the AI Run Ledger when implementation starts.",
            "",
            "## Verification Commands",
            "",
            "- `python -m unittest discover tools/dev-rag/tests` for Dev RAG changes.",
            "- Project-specific build and service checks should be selected from the related plan.",
            "",
            "## Sources",
            "",
            format_source_list(results),
            "",
        ]
    )


def format_context_list(results: Sequence[SearchResult]) -> str:
    if not results:
        return "- No direct match."
    lines = []
    for result in results[:5]:
        lines.append(f"- `{result.path}` > {result.heading_path}: {compact_excerpt(result.chunk_text, 180)}")
    return "\n".join(lines)


def format_source_list(results: Sequence[SearchResult]) -> str:
    if not results:
        return "- No sources."
    seen: set[str] = set()
    lines = []
    for result in results:
        if result.path in seen:
            continue
        seen.add(result.path)
        lines.append(f"- `{result.path}`")
    return "\n".join(lines)


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="StockRush development RAG CLI")
    parser.add_argument("--root", default=".", help="Project root path")
    parser.add_argument("--index", default=str(DEFAULT_INDEX), help="SQLite index path")
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("ingest", help="Index project documents")

    search_parser = subparsers.add_parser("search", help="Search indexed context")
    search_parser.add_argument("query")
    search_parser.add_argument("--limit", type=int, default=8)

    context_parser = subparsers.add_parser("context", help="Generate a Markdown Context Pack")
    context_parser.add_argument("goal")
    context_parser.add_argument("--limit", type=int, default=8)
    context_parser.add_argument("--output", help="Write Context Pack to this path")

    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    root = Path(args.root).expanduser().resolve()
    index_path = Path(args.index)
    if not index_path.is_absolute():
        index_path = root / index_path

    if args.command == "ingest":
        count = ingest(index_path, root, default_sources(root))
        print(f"Indexed {count} files into {display_path(index_path, root)}")
        return 0

    if args.command == "search":
        results = search(index_path, args.query, args.limit)
        print(format_search_results(results))
        return 0

    if args.command == "context":
        results = search(index_path, args.goal, args.limit)
        context = generate_context(args.goal, results)
        if args.output:
            output_path = Path(args.output)
            if not output_path.is_absolute():
                output_path = root / output_path
            output_path.parent.mkdir(parents=True, exist_ok=True)
            output_path.write_text(context, encoding="utf-8")
            print(f"Wrote {display_path(output_path, root)}")
        else:
            print(context)
        return 0

    raise AssertionError(f"Unsupported command: {args.command}")


if __name__ == "__main__":
    raise SystemExit(main())
