#!/usr/bin/env python3
"""Create and update AI Run Ledger records."""
from __future__ import annotations

import argparse
import re
import shutil
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Sequence


RUNS_DIR = Path(".ai-runs")
TEMPLATES_DIR = RUNS_DIR / "templates"
RUN_FILES = [
    "goal.md",
    "context-pack.md",
    "agent-plan.md",
    "decisions.md",
    "changed-files.md",
    "verification.md",
]


@dataclass(frozen=True)
class RunInfo:
    run_id: str
    path: Path


def slugify(value: str) -> str:
    lowered = value.strip().lower()
    replaced = re.sub(r"[^a-z0-9가-힣]+", "-", lowered)
    return replaced.strip("-") or "run"


def timestamp(now: datetime | None = None) -> str:
    current = now or datetime.now()
    return current.strftime("%Y-%m-%d-%H%M")


def runs_root(project_root: Path) -> Path:
    return project_root / RUNS_DIR


def templates_root(project_root: Path) -> Path:
    return project_root / TEMPLATES_DIR


def create_run(
    project_root: Path,
    slug: str,
    task: str,
    scope: str,
    out_of_scope: str,
    completion: str,
    now: datetime | None = None,
) -> RunInfo:
    run_id = f"{timestamp(now)}-{slugify(slug)}"
    run_path = runs_root(project_root) / run_id
    run_path.mkdir(parents=True, exist_ok=False)

    copy_templates(project_root, run_path)
    write_goal(run_path / "goal.md", task, scope, out_of_scope, completion)
    return RunInfo(run_id=run_id, path=run_path)


def copy_templates(project_root: Path, run_path: Path) -> None:
    template_root = templates_root(project_root)
    for file_name in RUN_FILES:
        source = template_root / file_name
        target = run_path / file_name
        if source.exists():
            shutil.copyfile(source, target)
        else:
            target.write_text(f"# {file_name}\n", encoding="utf-8")


def write_goal(path: Path, task: str, scope: str, out_of_scope: str, completion: str) -> None:
    path.write_text(
        "\n".join(
            [
                "# Goal",
                "",
                "## Task",
                "",
                task,
                "",
                "## Scope",
                "",
                scope,
                "",
                "## Out of Scope",
                "",
                out_of_scope,
                "",
                "## Completion Criteria",
                "",
                completion,
                "",
            ]
        ),
        encoding="utf-8",
    )


def list_runs(project_root: Path) -> list[RunInfo]:
    root = runs_root(project_root)
    if not root.exists():
        return []
    runs = []
    for path in sorted(root.iterdir()):
        if path.is_dir() and path.name != "templates":
            runs.append(RunInfo(run_id=path.name, path=path))
    return runs


def append_verification(project_root: Path, run_id: str, command: str, result: str, notes: str) -> Path:
    run_path = runs_root(project_root) / run_id
    if not run_path.exists() or not run_path.is_dir():
        raise FileNotFoundError(f"Run not found: {run_id}")
    verification_path = run_path / "verification.md"
    if not verification_path.exists():
        verification_path.write_text(
            "# Verification\n\n## Commands\n\n| Command | Result | Notes |\n|---|---|---|\n",
            encoding="utf-8",
        )
    row = f"| `{escape_table(command)}` | {escape_table(result)} | {escape_table(notes)} |\n"
    text = verification_path.read_text(encoding="utf-8")
    marker = "|---|---|---|\n"
    if marker in text:
        insert_at = text.index(marker) + len(marker)
        text = text[:insert_at] + row + text[insert_at:]
        verification_path.write_text(text, encoding="utf-8")
    else:
        with verification_path.open("a", encoding="utf-8") as output:
            output.write(row)
    return verification_path


def escape_table(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ")


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="StockRush Agent Runner")
    parser.add_argument("--root", default=".", help="Project root path")
    subparsers = parser.add_subparsers(dest="command", required=True)

    start_parser = subparsers.add_parser("start", help="Create an AI Run Ledger folder")
    start_parser.add_argument("--slug", required=True)
    start_parser.add_argument("--task", required=True)
    start_parser.add_argument("--scope", required=True)
    start_parser.add_argument("--out-of-scope", default="No additional scope.")
    start_parser.add_argument("--completion", required=True)

    subparsers.add_parser("list", help="List AI Run Ledger folders")

    verify_parser = subparsers.add_parser("verify", help="Append verification evidence")
    verify_parser.add_argument("run_id")
    verify_parser.add_argument("--command", dest="verification_command", required=True)
    verify_parser.add_argument("--result", required=True)
    verify_parser.add_argument("--notes", default="")

    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    root = Path(args.root).expanduser().resolve()

    if args.command == "start":
        run = create_run(
            project_root=root,
            slug=args.slug,
            task=args.task,
            scope=args.scope,
            out_of_scope=args.out_of_scope,
            completion=args.completion,
        )
        print(f"Created {run.path.relative_to(root)}")
        return 0

    if args.command == "list":
        runs = list_runs(root)
        if not runs:
            print("No runs.")
        else:
            for run in runs:
                print(run.run_id)
        return 0

    if args.command == "verify":
        path = append_verification(root, args.run_id, args.verification_command, args.result, args.notes)
        print(f"Updated {path.relative_to(root)}")
        return 0

    raise AssertionError(f"Unsupported command: {args.command}")


if __name__ == "__main__":
    raise SystemExit(main())
