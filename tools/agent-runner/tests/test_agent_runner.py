from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

# AI Run Ledger 생성/조회/검증 경로의 회귀 동작을 검증합니다.


MODULE_PATH = Path(__file__).resolve().parents[1] / "agent_runner.py"
SPEC = importlib.util.spec_from_file_location("agent_runner", MODULE_PATH)
assert SPEC and SPEC.loader
agent_runner = importlib.util.module_from_spec(SPEC)
sys.modules["agent_runner"] = agent_runner
SPEC.loader.exec_module(agent_runner)


class AgentRunnerTest(unittest.TestCase):
    def test_create_run_from_templates(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            templates = root / ".ai-runs" / "templates"
            templates.mkdir(parents=True)
            for file_name in agent_runner.RUN_FILES:
                (templates / file_name).write_text(f"# {file_name}\n", encoding="utf-8")

            run = agent_runner.create_run(
                project_root=root,
                slug="Order Saga",
                task="Implement order saga",
                scope="services/order-service",
                out_of_scope="frontend",
                completion="Order completes",
                now=datetime(2026, 5, 12, 17, 0),
            )

            self.assertEqual(run.run_id, "2026-05-12-1700-order-saga")
            self.assertTrue((run.path / "context-pack.md").exists())
            self.assertIn("Implement order saga", (run.path / "goal.md").read_text(encoding="utf-8"))

    def test_list_runs_excludes_templates(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".ai-runs" / "templates").mkdir(parents=True)
            (root / ".ai-runs" / "2026-05-12-1700-order-saga").mkdir()

            runs = agent_runner.list_runs(root)

            self.assertEqual([run.run_id for run in runs], ["2026-05-12-1700-order-saga"])

    def test_append_verification(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run_path = root / ".ai-runs" / "2026-05-12-1700-order-saga"
            run_path.mkdir(parents=True)
            (run_path / "verification.md").write_text(
                "# Verification\n\n## Commands\n\n| Command | Result | Notes |\n|---|---|---|\n",
                encoding="utf-8",
            )

            path = agent_runner.append_verification(
                root,
                "2026-05-12-1700-order-saga",
                "python -m unittest",
                "PASS",
                "8 tests",
            )

            self.assertIn("8 tests", path.read_text(encoding="utf-8"))
            self.assertLess(
                path.read_text(encoding="utf-8").index("8 tests"),
                path.read_text(encoding="utf-8").index("## Manual Checks")
                if "## Manual Checks" in path.read_text(encoding="utf-8")
                else len(path.read_text(encoding="utf-8")),
            )

    def test_missing_run_fails_verification_append(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)

            with self.assertRaises(FileNotFoundError):
                agent_runner.append_verification(root, "missing", "cmd", "PASS", "")

    def test_verify_cli_uses_verification_command_dest(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run_path = root / ".ai-runs" / "2026-05-12-1700-order-saga"
            run_path.mkdir(parents=True)

            result = agent_runner.main(
                [
                    "--root",
                    str(root),
                    "verify",
                    "2026-05-12-1700-order-saga",
                    "--command",
                    "python -m unittest",
                    "--result",
                    "PASS",
                    "--notes",
                    "ok",
                ]
            )

            self.assertEqual(result, 0)
            self.assertIn("python -m unittest", (run_path / "verification.md").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
