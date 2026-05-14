#!/usr/bin/env python3
"""Architecture boundary checks for StockRush."""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Sequence


SERVICE_SCHEMAS = {
    "auth-service": "auth",
    "catalog-service": "catalog",
    "inventory-service": "inventory",
    "order-service": "orders",
    "payment-service": "payment",
    "promotion-service": "promotion",
    "fulfillment-service": "fulfillment",
    "read-model-service": "read_model",
}
EVENT_REQUIRED_FIELDS = {
    "eventId",
    "eventType",
    "eventVersion",
    "aggregateId",
    "correlationId",
    "idempotencyKey",
    "occurredAt",
    "payload",
}
OUTBOX_REQUIRED_COLUMNS = {
    "event_id",
    "aggregate_id",
    "event_type",
    "event_version",
    "payload",
    "status",
    "retry_count",
    "error_message",
    "created_at",
    "published_at",
}
OUTBOX_EVENTS_TABLE_PATTERN = re.compile(
    r"\bcreate\s+table\s+(?:if\s+not\s+exists\s+)?(?:[a-z_][a-z0-9_]*\.)?outbox_events\b",
    re.IGNORECASE,
)
EXCLUDED_DIRS = {".git", ".worktrees", ".dev-rag", "target", "build", "node_modules", "dist", "coverage"}
SQL_SCHEMA_REFERENCE_PREFIXES = (
    r"from",
    r"join",
    r"update",
    r"into",
    r"references",
    r"truncate\s+table",
    r"delete\s+from",
    r"merge\s+into",
    r"alter\s+table",
    r"create\s+(?:table|view|index|unique\s+index)",
    r"drop\s+(?:table|view|index)",
)


@dataclass(frozen=True)
class Violation:
    rule_id: str
    severity: str
    file: str
    message: str
    suggested_fix: str


def iter_source_files(root: Path) -> Iterable[Path]:
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        try:
            relative_parts = path.relative_to(root).parts
        except ValueError:
            relative_parts = path.parts
        if any(part in EXCLUDED_DIRS for part in relative_parts):
            continue
        if path.suffix in {".java", ".sql"}:
            yield path


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def display_path(root: Path, path: Path) -> str:
    try:
        return str(path.resolve().relative_to(root.resolve()))
    except ValueError:
        return str(path)


def discover_entities(root: Path) -> set[str]:
    entities: set[str] = set()
    for path in iter_source_files(root):
        if path.suffix != ".java":
            continue
        text = read_text(path)
        if "@Entity" not in text:
            continue
        match = re.search(r"\bclass\s+([A-Z][A-Za-z0-9_]*)\b", text)
        if match:
            entities.add(match.group(1))
    return entities


def check_controller_entity_returns(root: Path, entities: set[str]) -> list[Violation]:
    violations: list[Violation] = []
    if not entities:
        return violations

    for path in iter_source_files(root):
        if path.suffix != ".java":
            continue
        text = read_text(path)
        if "@RestController" not in text and "@Controller" not in text:
            continue
        for return_type, method_name in re.findall(r"\bpublic\s+([A-Z][A-Za-z0-9_<>, ?]*)\s+([a-zA-Z0-9_]+)\s*\(", text):
            normalized = normalize_return_type(return_type)
            if normalized in entities:
                violations.append(
                    Violation(
                        rule_id="ARCH-002",
                        severity="error",
                        file=display_path(root, path),
                        message=f"Controller method `{method_name}` returns JPA entity `{normalized}` directly.",
                        suggested_fix="Return a response DTO and map the entity in the application layer.",
                    )
                )
    return violations


def normalize_return_type(return_type: str) -> str:
    cleaned = return_type.strip()
    generic_match = re.match(r"(?:ResponseEntity|Optional|List|Collection|Set)<\s*([A-Z][A-Za-z0-9_]*)", cleaned)
    if generic_match:
        return generic_match.group(1)
    return re.sub(r"[^A-Za-z0-9_].*$", "", cleaned)


def check_event_envelopes(root: Path) -> list[Violation]:
    violations: list[Violation] = []
    for path in iter_source_files(root):
        if path.suffix != ".java":
            continue
        text = read_text(path)
        if not is_event_file(path, text):
            continue
        missing = sorted(field for field in EVENT_REQUIRED_FIELDS if not re.search(rf"\b{re.escape(field)}\b", text))
        if missing:
            violations.append(
                Violation(
                    rule_id="ARCH-003",
                    severity="error",
                    file=display_path(root, path),
                    message=f"Kafka event is missing envelope fields: {', '.join(missing)}.",
                    suggested_fix="Wrap payloads with the common event envelope before publishing.",
                )
            )
    return violations


def is_event_file(path: Path, text: str) -> bool:
    if path.name.endswith("Event.java") and "Envelope" not in path.name:
        return True
    return bool(re.search(r"\b(class|record)\s+[A-Z][A-Za-z0-9_]*Event\b", text))


def check_outbox_tables(root: Path) -> list[Violation]:
    violations: list[Violation] = []
    for path in iter_source_files(root):
        if path.suffix != ".sql":
            continue
        text = read_text(path)
        if not OUTBOX_EVENTS_TABLE_PATTERN.search(text):
            continue
        lower = text.lower()
        missing = sorted(column for column in OUTBOX_REQUIRED_COLUMNS if column not in lower)
        if missing:
            violations.append(
                Violation(
                    rule_id="ARCH-004",
                    severity="error",
                    file=display_path(root, path),
                    message=f"Outbox table is missing columns: {', '.join(missing)}.",
                    suggested_fix="Add the required Outbox columns before using this table for event relay.",
                )
            )
    return violations


def check_schema_ownership(root: Path) -> list[Violation]:
    violations: list[Violation] = []
    schema_names = set(SERVICE_SCHEMAS.values())
    for path in iter_source_files(root):
        service_name = service_for_path(root, path)
        if service_name is None:
            continue
        owned_schema = SERVICE_SCHEMAS.get(service_name)
        if owned_schema is None:
            continue
        text = read_text(path)
        for schema in schema_names - {owned_schema}:
            if has_schema_qualified_sql_reference(text, schema):
                violations.append(
                    Violation(
                        rule_id="ARCH-001",
                        severity="error",
                        file=display_path(root, path),
                        message=f"`{service_name}` appears to access `{schema}` schema directly.",
                        suggested_fix="Use service API, Kafka event, or read model instead of direct schema access.",
                    )
                )
    return violations


def has_schema_qualified_sql_reference(text: str, schema: str) -> bool:
    prefixes = "|".join(SQL_SCHEMA_REFERENCE_PREFIXES)
    pattern = re.compile(
        rf"\b(?:{prefixes})\s+{re.escape(schema)}\s*\.\s*[a-z_][a-z0-9_]*\b",
        re.IGNORECASE,
    )
    return bool(pattern.search(text))


def service_for_path(root: Path, path: Path) -> str | None:
    try:
        parts = path.resolve().relative_to(root.resolve()).parts
    except ValueError:
        return None
    if len(parts) >= 2 and parts[0] == "services":
        return parts[1]
    return None


def check(root: Path) -> list[Violation]:
    entities = discover_entities(root)
    violations: list[Violation] = []
    violations.extend(check_schema_ownership(root))
    violations.extend(check_controller_entity_returns(root, entities))
    violations.extend(check_event_envelopes(root))
    violations.extend(check_outbox_tables(root))
    return violations


def format_text(violations: Sequence[Violation]) -> str:
    if not violations:
        return "Architecture Guard passed."
    blocks = []
    for violation in violations:
        blocks.append(
            "\n".join(
                [
                    f"{violation.rule_id} [{violation.severity}] {violation.file}",
                    f"message: {violation.message}",
                    f"fix: {violation.suggested_fix}",
                ]
            )
        )
    return "\n\n".join(blocks)


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="StockRush Architecture Guard")
    parser.add_argument("--root", default=".", help="Project root path")
    subparsers = parser.add_subparsers(dest="command", required=True)
    check_parser = subparsers.add_parser("check", help="Run architecture checks")
    check_parser.add_argument("--format", choices=["text", "json"], default="text")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    root = Path(args.root).expanduser().resolve()

    if args.command == "check":
        violations = check(root)
        if args.format == "json":
            print(json.dumps([asdict(violation) for violation in violations], ensure_ascii=False, indent=2))
        else:
            print(format_text(violations))
        return 1 if violations else 0

    raise AssertionError(f"Unsupported command: {args.command}")


if __name__ == "__main__":
    raise SystemExit(main())
