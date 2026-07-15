#!/usr/bin/env python3
"""Generate deterministic portfolio evidence from Maven Surefire XML reports."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from typing import Mapping, Sequence
import xml.etree.ElementTree as ET


SCHEMA_VERSION = 2
COUNT_FIELDS = ("tests", "failures", "errors", "skipped")
OUTCOME_ELEMENTS = ("failure", "error", "skipped")
MAX_REPORT_BYTES = 10 * 1024 * 1024
GIT_COMMIT_PATTERN = re.compile(r"[0-9a-fA-F]{7,64}")
COMMIT_ENV_VARS = (
    "PORTFOLIO_EVIDENCE_GIT_COMMIT",
    "GITHUB_SHA",
    "CI_COMMIT_SHA",
    "GIT_COMMIT",
)


class EvidenceError(ValueError):
    """Raised when a report cannot be generated from the supplied evidence."""


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _display_path(path: Path, base_dir: Path) -> str:
    resolved = path.resolve()
    try:
        return resolved.relative_to(base_dir.resolve()).as_posix()
    except ValueError:
        return resolved.as_posix()


def _validate_label(value: str, field: str) -> str:
    normalized = value.strip()
    if not normalized:
        raise EvidenceError(f"{field} must not be empty")
    if len(normalized) > 200 or any(ord(character) < 32 for character in normalized):
        raise EvidenceError(f"{field} contains unsupported characters")
    return normalized


def _read_source(
    path: Path, base_dir: Path
) -> tuple[dict[str, object], bytes]:
    try:
        size = path.stat().st_size
    except OSError as exc:
        raise EvidenceError(f"failed to inspect Surefire XML '{path}': {exc}") from exc
    if size > MAX_REPORT_BYTES:
        raise EvidenceError(
            f"Surefire XML exceeds {MAX_REPORT_BYTES} bytes: "
            f"{_display_path(path, base_dir)}"
        )

    try:
        content = path.read_bytes()
    except OSError as exc:
        raise EvidenceError(f"failed to read Surefire XML '{path}': {exc}") from exc
    if len(content) != size:
        raise EvidenceError(
            f"Surefire XML changed while being read: {_display_path(path, base_dir)}"
        )
    return (
        {
            "path": _display_path(path, base_dir),
            "sha256": hashlib.sha256(content).hexdigest(),
            "bytes": size,
        },
        content,
    )


def discover_report_files(inputs: Sequence[Path | str]) -> list[Path]:
    if not inputs:
        raise EvidenceError("no Surefire inputs provided")

    reports: dict[Path, Path] = {}
    for raw_input in inputs:
        input_path = Path(raw_input).expanduser()
        if not input_path.exists():
            raise EvidenceError(f"Surefire input does not exist: {input_path}")

        if input_path.is_file():
            if input_path.suffix.lower() != ".xml":
                raise EvidenceError(f"Surefire input is not an XML file: {input_path}")
            candidates = [input_path]
        elif input_path.is_dir():
            candidates = input_path.rglob("TEST-*.xml")
        else:
            raise EvidenceError(f"Surefire input is not a file or directory: {input_path}")

        for candidate in candidates:
            resolved = candidate.resolve()
            reports[resolved] = resolved

    ordered = sorted(reports.values(), key=lambda path: path.as_posix())
    if not ordered:
        rendered_inputs = ", ".join(str(path) for path in inputs)
        raise EvidenceError(f"no Surefire XML reports found in: {rendered_inputs}")
    return ordered


def _parse_count(element: ET.Element, field: str, source: str) -> int:
    raw_value = element.get(field)
    if raw_value is None:
        raise EvidenceError(f"missing '{field}' in Surefire suite from {source}")
    try:
        value = int(raw_value)
    except ValueError as exc:
        raise EvidenceError(
            f"invalid '{field}' value {raw_value!r} in Surefire suite from {source}"
        ) from exc
    if value < 0:
        raise EvidenceError(
            f"negative '{field}' value {raw_value!r} in Surefire suite from {source}"
        )
    return value


def _parse_duration(element: ET.Element, source: str) -> float:
    raw_value = element.get("time", "0")
    try:
        value = float(raw_value)
    except ValueError as exc:
        raise EvidenceError(
            f"invalid 'time' value {raw_value!r} in Surefire suite from {source}"
        ) from exc
    if not math.isfinite(value) or value < 0:
        raise EvidenceError(
            f"invalid 'time' value {raw_value!r} in Surefire suite from {source}"
        )
    return value


def _validate_testcases(
    element: ET.Element,
    counts: Mapping[str, int],
    suite_name: str,
    source: str,
) -> None:
    testcases = [
        child for child in element if _local_name(child.tag) == "testcase"
    ]
    if len(testcases) != counts["tests"]:
        raise EvidenceError(
            f"testcase count does not match tests in Surefire suite "
            f"'{suite_name}' from {source}"
        )

    observed = {outcome: 0 for outcome in OUTCOME_ELEMENTS}
    for testcase in testcases:
        outcomes = [
            _local_name(child.tag)
            for child in testcase
            if _local_name(child.tag) in OUTCOME_ELEMENTS
        ]
        if len(outcomes) > 1:
            testcase_name = (testcase.get("name") or "<unnamed>").strip()
            raise EvidenceError(
                f"multiple outcomes in testcase '{testcase_name}' from {source}"
            )
        if outcomes:
            observed[outcomes[0]] += 1

    for field in ("failures", "errors", "skipped"):
        outcome = field[:-1] if field != "skipped" else "skipped"
        if observed[outcome] != counts[field]:
            raise EvidenceError(
                f"observed {outcome} count does not match '{field}' in Surefire "
                f"suite '{suite_name}' from {source}"
            )


def parse_surefire_xml(
    path: Path,
    base_dir: Path,
    content: bytes | None = None,
) -> list[dict[str, object]]:
    source = _display_path(path, base_dir)
    if content is None:
        _, content = _read_source(path, base_dir)
    try:
        root = ET.fromstring(content)
    except ET.ParseError as exc:
        raise EvidenceError(f"failed to parse Surefire XML '{source}': {exc}") from exc

    root_name = _local_name(root.tag)
    if root_name == "testsuite":
        suite_elements = [root]
    elif root_name == "testsuites":
        suite_elements = [
            element for element in root if _local_name(element.tag) == "testsuite"
        ]
    else:
        raise EvidenceError(
            f"unsupported Surefire XML root '{root_name}' in {source}; "
            "expected testsuite or testsuites"
        )

    if not suite_elements:
        raise EvidenceError(f"no testsuite elements found in Surefire XML: {source}")

    suites: list[dict[str, object]] = []
    for element in suite_elements:
        name = (element.get("name") or "").strip()
        if not name:
            raise EvidenceError(f"missing 'name' in Surefire suite from {source}")

        counts = {field: _parse_count(element, field, source) for field in COUNT_FIELDS}
        nested_suites = [
            child for child in element if _local_name(child.tag) == "testsuite"
        ]
        if nested_suites:
            raise EvidenceError(
                f"nested testsuite is not supported in Surefire XML: {source}"
            )
        passed = (
            counts["tests"]
            - counts["failures"]
            - counts["errors"]
            - counts["skipped"]
        )
        if passed < 0:
            raise EvidenceError(
                f"inconsistent test counts in Surefire suite '{name}' from {source}"
            )
        _validate_testcases(element, counts, name, source)

        suites.append(
            {
                "name": name,
                "source": source,
                "tests": counts["tests"],
                "failures": counts["failures"],
                "errors": counts["errors"],
                "skipped": counts["skipped"],
                "passed": passed,
                "duration_seconds": _parse_duration(element, source),
            }
        )
    return suites


def resolve_git_commit(environ: Mapping[str, str], repo_dir: Path) -> str:
    for variable in COMMIT_ENV_VARS:
        value = environ.get(variable, "").strip()
        if value:
            if not GIT_COMMIT_PATTERN.fullmatch(value):
                raise EvidenceError(
                    f"invalid git commit in environment variable {variable}: {value!r}"
                )
            return value.lower()

    try:
        result = subprocess.run(
            ["git", "-C", str(repo_dir), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise EvidenceError(
            "unable to determine git commit from environment or git"
        ) from exc

    commit = result.stdout.strip()
    if not GIT_COMMIT_PATTERN.fullmatch(commit):
        raise EvidenceError(f"git returned an invalid commit identifier: {commit!r}")
    return commit.lower()


def resolve_generated_at_utc(environ: Mapping[str, str]) -> str:
    source_date_epoch = environ.get("SOURCE_DATE_EPOCH", "").strip()
    if source_date_epoch:
        try:
            timestamp = int(source_date_epoch)
            if timestamp < 0:
                raise ValueError("negative epoch")
            generated_at = datetime.fromtimestamp(timestamp, tz=timezone.utc)
        except (ValueError, OverflowError, OSError) as exc:
            raise EvidenceError(
                f"invalid SOURCE_DATE_EPOCH value: {source_date_epoch!r}"
            ) from exc
    else:
        generated_at = datetime.now(timezone.utc)

    return generated_at.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def build_report(
    *,
    project: str,
    scope: str,
    inputs: Sequence[Path | str],
    environ: Mapping[str, str] | None = None,
    repo_dir: Path | None = None,
    require_success: bool = False,
) -> dict[str, object]:
    project = _validate_label(project, "project")
    scope = _validate_label(scope, "scope")

    effective_environ = os.environ if environ is None else environ
    effective_repo_dir = Path.cwd() if repo_dir is None else repo_dir
    report_files = discover_report_files(inputs)
    loaded_sources = [
        (report_file, *_read_source(report_file, effective_repo_dir))
        for report_file in report_files
    ]
    source_files = [metadata for _, metadata, _ in loaded_sources]

    suites: list[dict[str, object]] = []
    for report_file, _, content in loaded_sources:
        suites.extend(parse_surefire_xml(report_file, effective_repo_dir, content))
    suites.sort(key=lambda suite: (str(suite["name"]), str(suite["source"])))
    suite_identities = [(str(suite["name"]), str(suite["source"])) for suite in suites]
    if len(suite_identities) != len(set(suite_identities)):
        raise EvidenceError("duplicate Surefire suite name and source detected")

    totals = {
        field: sum(int(suite[field]) for suite in suites)
        for field in ("tests", "failures", "errors", "skipped", "passed")
    }
    status = "passed" if totals["failures"] == 0 and totals["errors"] == 0 else "failed"
    if require_success and status != "passed":
        raise EvidenceError(
            "Surefire evidence contains failed or errored tests while success is required"
        )
    return {
        "schema_version": SCHEMA_VERSION,
        "project": project,
        "git_commit": resolve_git_commit(effective_environ, effective_repo_dir),
        "generated_at_utc": resolve_generated_at_utc(effective_environ),
        "scope": scope,
        "status": status,
        "source_file_count": len(source_files),
        "suite_count": len(suites),
        "source_files": source_files,
        "totals": totals,
        "suites": suites,
    }


def render_report(report: Mapping[str, object]) -> str:
    return json.dumps(report, ensure_ascii=True, indent=2) + "\n"


def write_report(report: Mapping[str, object], output: Path | None) -> None:
    rendered = render_report(report)
    if output is None:
        sys.stdout.write(rendered)
        return

    output = output.expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=output.parent,
            prefix=f".{output.name}.",
            delete=False,
        ) as handle:
            handle.write(rendered)
            handle.flush()
            os.fsync(handle.fileno())
            temporary_path = Path(handle.name)
        os.replace(temporary_path, output)
    finally:
        if temporary_path is not None and temporary_path.exists():
            temporary_path.unlink()


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Generate portfolio evidence JSON from Maven Surefire XML reports."
    )
    parser.add_argument("--project", required=True, help="Project name for the report")
    parser.add_argument("--scope", required=True, help="Verified scope label")
    parser.add_argument(
        "--output",
        type=Path,
        help="Output JSON path; writes to stdout when omitted",
    )
    parser.add_argument(
        "--require-success",
        action="store_true",
        help="Reject reports containing failed or errored tests",
    )
    parser.add_argument(
        "inputs",
        nargs="*",
        type=Path,
        help="Surefire XML files or directories containing TEST-*.xml reports",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_parser().parse_args(argv)
    try:
        report = build_report(
            project=args.project,
            scope=args.scope,
            inputs=args.inputs,
            require_success=args.require_success,
        )
        write_report(report, args.output)
    except EvidenceError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
