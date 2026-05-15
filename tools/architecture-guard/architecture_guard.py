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
OBSERVABLE_SERVICES = (
    "catalog-service",
    "inventory-service",
    "order-service",
    "payment-service",
    "promotion-service",
    "fulfillment-service",
    "read-model-service",
    "gateway",
)
API_CORRELATION_SERVICES = (
    "catalog-service",
    "inventory-service",
    "order-service",
    "payment-service",
    "promotion-service",
    "fulfillment-service",
    "read-model-service",
)
ACTUATOR_REQUIRED_EXPOSURES = {"health", "info", "metrics"}
CORRELATION_ID_HEADER = "X-Correlation-Id"
CORRELATION_MDC_KEY = "correlationId"
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


def check_actuator_observability(root: Path) -> list[Violation]:
    violations: list[Violation] = []
    services_root = root / "services"
    if not services_root.exists():
        return violations

    for service_name in OBSERVABLE_SERVICES:
        service_root = services_root / service_name
        if not service_root.exists():
            continue

        pom_path = service_root / "pom.xml"
        if not pom_path.exists() or "spring-boot-starter-actuator" not in read_text(pom_path):
            violations.append(
                Violation(
                    rule_id="ARCH-009",
                    severity="error",
                    file=display_path(root, pom_path),
                    message=f"`{service_name}` must include Spring Boot Actuator.",
                    suggested_fix="Add spring-boot-starter-actuator to the service dependencies.",
                )
            )

        config_paths = actuator_config_paths(service_root)
        if not config_paths:
            violations.append(
                Violation(
                    rule_id="ARCH-009",
                    severity="error",
                    file=display_path(root, service_root),
                    message=f"`{service_name}` must define Actuator endpoint exposure.",
                    suggested_fix="Expose health, info, and metrics through application.yml or application.properties.",
                )
            )
            continue

        if not any(exposes_required_actuator_endpoints(read_text(path)) for path in config_paths):
            violations.append(
                Violation(
                    rule_id="ARCH-009",
                    severity="error",
                    file=display_path(root, config_paths[0]),
                    message=f"`{service_name}` Actuator exposure must include health, info, and metrics.",
                    suggested_fix="Set management.endpoints.web.exposure.include to health,info,metrics.",
                )
            )
    return violations


def check_gateway_security_routes(root: Path) -> list[Violation]:
    gateway_root = root / "services" / "gateway"
    if not gateway_root.exists():
        return []

    security_config = gateway_root / "src" / "main" / "java" / "com" / "stockrush" / "gateway" / "config" / "SecurityConfig.java"
    if not security_config.exists():
        return [
            Violation(
                rule_id="ARCH-011",
                severity="error",
                file=display_path(root, gateway_root),
                message="Gateway must define SecurityConfig for protected admin and customer routes.",
                suggested_fix="Add SecurityConfig with stateless OAuth2 Resource Server JWT route authorization.",
            )
        ]

    text = read_text(security_config)
    required_patterns = {
        "OAuth2 Resource Server JWT": r"\.oauth2ResourceServer\s*\(",
        "stateless sessions": r"SessionCreationPolicy\.STATELESS",
        "admin routes": r'requestMatchers\s*\(\s*"/api/admin/\*\*"\s*,\s*"/api/read-model/admin/\*\*"\s*\)\s*\.hasRole\s*\(\s*"ADMIN"\s*\)',
        "customer order create": r"requestMatchers\s*\(\s*HttpMethod\.POST\s*,\s*\"/api/orders\"\s*\)\s*\.hasRole\s*\(\s*\"CUSTOMER\"\s*\)",
        "customer order reads": r"requestMatchers\s*\(\s*HttpMethod\.GET\s*,\s*\"/api/orders/\*\*\"\s*,\s*\"/api/read-model/orders\"\s*\)\s*\.hasRole\s*\(\s*\"CUSTOMER\"\s*\)",
    }
    missing = [name for name, pattern in required_patterns.items() if not re.search(pattern, text, re.DOTALL)]
    if not missing:
        return []

    return [
        Violation(
            rule_id="ARCH-011",
            severity="error",
            file=display_path(root, security_config),
            message=f"Gateway security route authorization is missing: {', '.join(missing)}.",
            suggested_fix="Keep admin routes under ROLE_ADMIN and customer order/read-model routes under ROLE_CUSTOMER with JWT resource server enabled.",
        )
    ]


def check_gateway_trusted_identity_headers(root: Path) -> list[Violation]:
    gateway_root = root / "services" / "gateway"
    if not gateway_root.exists():
        return []

    trusted_headers = gateway_root / "src" / "main" / "java" / "com" / "stockrush" / "gateway" / "api" / "TrustedIdentityHeaders.java"
    if not trusted_headers.exists():
        return [
            Violation(
                rule_id="ARCH-012",
                severity="error",
                file=display_path(root, gateway_root),
                message="Gateway must own trusted internal identity headers.",
                suggested_fix="Add TrustedIdentityHeaders that removes client supplied identity headers and derives subject/operator from JWT.",
            )
        ]

    text = read_text(trusted_headers)
    required_patterns = {
        "subject header": r'X-StockRush-Subject',
        "email header": r'X-StockRush-Email',
        "roles header": r'X-StockRush-Roles',
        "operator header": r'X-Operator-Id',
        "remove subject": r"\.remove\s*\(\s*SUBJECT\s*\)",
        "remove email": r"\.remove\s*\(\s*EMAIL\s*\)",
        "remove roles": r"\.remove\s*\(\s*ROLES\s*\)",
        "remove operator": r"\.remove\s*\(\s*OPERATOR\s*\)",
        "set subject": r"\.set\s*\(\s*SUBJECT\s*,\s*jwt\.getSubject\s*\(\s*\)\s*\)",
        "set admin operator": r"\.set\s*\(\s*OPERATOR\s*,\s*operatorId\s*\(\s*jwt\s*\)\s*\)",
        "email claim": r"jwt\.getClaimAsString\s*\(\s*\"email\"\s*\)",
        "subject fallback": r"return\s+jwt\.getSubject\s*\(\s*\)",
    }
    missing = [name for name, pattern in required_patterns.items() if not re.search(pattern, text, re.DOTALL)]
    if not missing:
        return []

    return [
        Violation(
            rule_id="ARCH-012",
            severity="error",
            file=display_path(root, trusted_headers),
            message=f"Gateway trusted identity header handling is missing: {', '.join(missing)}.",
            suggested_fix="Remove client supplied identity/operator headers and set internal headers from the authenticated JWT.",
        )
    ]


def check_correlation_id_propagation(root: Path) -> list[Violation]:
    violations: list[Violation] = []
    gateway_root = root / "services" / "gateway"
    if not gateway_root.exists():
        return violations

    gateway_java_files = [
        path
        for path in iter_source_files(root)
        if path.suffix == ".java" and service_for_path(root, path) == "gateway" and is_main_java_file(root, path)
    ]
    if any(is_gateway_correlation_filter(read_text(path)) for path in gateway_java_files):
        return violations

    violations.append(
        Violation(
            rule_id="ARCH-007",
            severity="error",
            file=display_path(root, gateway_root),
            message="Gateway must create and propagate X-Correlation-Id at the HTTP boundary.",
            suggested_fix="Add a OncePerRequestFilter that resolves X-Correlation-Id, stores it in MDC, and wraps request headers before proxy forwarding.",
        )
    )
    return violations


def check_api_service_correlation_mdc(root: Path) -> list[Violation]:
    violations: list[Violation] = []
    services_root = root / "services"
    if not services_root.exists():
        return violations

    for service_name in API_CORRELATION_SERVICES:
        service_root = services_root / service_name
        if not service_root.exists():
            continue

        service_java_files = [
            path
            for path in iter_source_files(root)
            if path.suffix == ".java" and service_for_path(root, path) == service_name and is_main_java_file(root, path)
        ]
        if not any("@RestController" in read_text(path) for path in service_java_files):
            continue
        if any(is_api_service_correlation_filter(read_text(path)) for path in service_java_files):
            continue

        violations.append(
            Violation(
                rule_id="ARCH-010",
                severity="error",
                file=display_path(root, service_root),
                message=f"`{service_name}` must keep X-Correlation-Id in request-scope MDC.",
                suggested_fix="Add a service-local OncePerRequestFilter that resolves X-Correlation-Id, stores it in MDC, wraps request headers, and clears MDC in finally.",
            )
        )
    return violations


def is_gateway_correlation_filter(text: str) -> bool:
    required_patterns = [
        r"extends\s+OncePerRequestFilter\b",
        r"\bdoFilterInternal\s*\(",
        r"\bresponse\.setHeader\s*\(",
        r"\bfilterChain\.doFilter\s*\(\s*new\s+[A-Za-z0-9_]*Correlation[A-Za-z0-9_]*Request\b",
        r"extends\s+HttpServletRequestWrapper\b",
        rf"{re.escape(CORRELATION_ID_HEADER)}",
        rf"{re.escape(CORRELATION_MDC_KEY)}",
        r"\bMDC\.put\s*\(",
        r"\bfinally\s*\{.*?\bMDC\.remove\s*\(",
        r"\bgetHeader\s*\(",
        r"\bgetHeaders\s*\(",
        r"\bgetHeaderNames\s*\(",
    ]
    return all(re.search(pattern, text, re.DOTALL) for pattern in required_patterns)


def is_api_service_correlation_filter(text: str) -> bool:
    required_patterns = [
        r"@Component\b",
        r"@Order\s*\(\s*Ordered\.HIGHEST_PRECEDENCE\s*\)",
        r"extends\s+OncePerRequestFilter\b",
        r"\bdoFilterInternal\s*\(",
        r"\bCorrelationIds\.resolve\s*\(",
        r"\bresponse\.setHeader\s*\(",
        r"(?:CorrelationIds\.HEADER_NAME|X-Correlation-Id)",
        rf"{re.escape(CORRELATION_MDC_KEY)}",
        r"\bMDC\.put\s*\(",
        r"\bfinally\s*\{.*?\bMDC\.remove\s*\(",
        r"\bfilterChain\.doFilter\s*\(\s*new\s+[A-Za-z0-9_]*Correlation[A-Za-z0-9_]*Request\b",
        r"extends\s+HttpServletRequestWrapper\b",
        r"\bgetHeader\s*\(",
        r"\bgetHeaders\s*\(",
        r"\bgetHeaderNames\s*\(",
    ]
    return all(re.search(pattern, text, re.DOTALL) for pattern in required_patterns)


def actuator_config_paths(service_root: Path) -> list[Path]:
    resource_root = service_root / "src" / "main" / "resources"
    return [path for path in [
        resource_root / "application.yml",
        resource_root / "application.yaml",
        resource_root / "application.properties",
    ] if path.exists()]


def exposes_required_actuator_endpoints(text: str) -> bool:
    values: list[str] = []
    lines = text.splitlines()
    for index, line in enumerate(lines):
        include_match = re.match(r"^(\s*)include\s*:\s*([^\n#]*)", line)
        if not include_match:
            continue
        inline_value = include_match.group(2).strip()
        if inline_value:
            values.extend(split_exposure_values(inline_value))
            continue

        include_indent = len(include_match.group(1))
        for child_line in lines[index + 1:]:
            child_without_comment = child_line.split("#", 1)[0]
            if not child_without_comment.strip():
                continue
            child_indent = len(child_without_comment) - len(child_without_comment.lstrip())
            if child_indent <= include_indent:
                break
            list_item = re.match(r"^\s*-\s*(.+?)\s*$", child_without_comment)
            if list_item:
                values.extend(split_exposure_values(list_item.group(1)))
    for match in re.finditer(r"(?m)^\s*management\.endpoints\.web\.exposure\.include\s*=\s*([^\n#]+)", text):
        values.extend(split_exposure_values(match.group(1)))

    normalized = {value.lower() for value in values}
    return "*" in normalized or ACTUATOR_REQUIRED_EXPOSURES.issubset(normalized)


def split_exposure_values(raw: str) -> list[str]:
    cleaned = raw.strip().strip("'\"[]")
    return [part.strip().strip("'\"") for part in cleaned.split(",") if part.strip()]


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


def is_main_java_file(root: Path, path: Path) -> bool:
    try:
        parts = path.resolve().relative_to(root.resolve()).parts
    except ValueError:
        return False
    return "src" in parts and "main" in parts and "java" in parts


def check(root: Path) -> list[Violation]:
    entities = discover_entities(root)
    violations: list[Violation] = []
    violations.extend(check_schema_ownership(root))
    violations.extend(check_controller_entity_returns(root, entities))
    violations.extend(check_event_envelopes(root))
    violations.extend(check_outbox_tables(root))
    violations.extend(check_correlation_id_propagation(root))
    violations.extend(check_api_service_correlation_mdc(root))
    violations.extend(check_actuator_observability(root))
    violations.extend(check_gateway_security_routes(root))
    violations.extend(check_gateway_trusted_identity_headers(root))
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
