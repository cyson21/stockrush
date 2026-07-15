from __future__ import annotations

from contextlib import redirect_stderr
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


TOOL_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOL_DIR))

import generate_report  # noqa: E402


class GenerateReportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.environment = {
            "PORTFOLIO_EVIDENCE_GIT_COMMIT": "0123456789abcdef",
            "SOURCE_DATE_EPOCH": "0",
        }

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write_suite(
        self,
        relative_path: str,
        *,
        name: str,
        tests: int,
        failures: int = 0,
        errors: int = 0,
        skipped: int = 0,
        duration: str = "0.1",
    ) -> Path:
        report_path = self.root / relative_path
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(
            (
                f'<testsuite name="{name}" tests="{tests}" failures="{failures}" '
                f'errors="{errors}" skipped="{skipped}" time="{duration}">'
                "</testsuite>"
            ),
            encoding="utf-8",
        )
        return report_path

    def build(self, inputs: list[Path]) -> dict[str, object]:
        return generate_report.build_report(
            project="StockRush",
            scope="order-service",
            inputs=inputs,
            environ=self.environment,
            repo_dir=self.root,
        )

    def test_aggregates_totals_and_sorts_suites_by_name(self) -> None:
        reports = self.root / "reports"
        self.write_suite(
            "reports/TEST-zeta.xml",
            name="zeta.Suite",
            tests=4,
            failures=1,
            skipped=1,
            duration="1.25",
        )
        self.write_suite(
            "reports/TEST-alpha.xml",
            name="alpha.Suite",
            tests=3,
            errors=1,
            duration="0.5",
        )

        report = self.build([reports])

        self.assertEqual(
            report["totals"],
            {"tests": 7, "failures": 1, "errors": 1, "skipped": 1, "passed": 4},
        )
        suites = report["suites"]
        self.assertEqual([suite["name"] for suite in suites], ["alpha.Suite", "zeta.Suite"])
        self.assertEqual(suites[0]["duration_seconds"], 0.5)

    def test_report_has_stable_top_level_and_suite_key_order(self) -> None:
        report_path = self.write_suite(
            "TEST-example.xml", name="example.Suite", tests=1
        )

        report = self.build([report_path])

        self.assertEqual(
            list(report),
            [
                "schema_version",
                "project",
                "git_commit",
                "generated_at_utc",
                "scope",
                "totals",
                "suites",
            ],
        )
        self.assertEqual(
            list(report["suites"][0]),
            [
                "name",
                "source",
                "tests",
                "failures",
                "errors",
                "skipped",
                "passed",
                "duration_seconds",
            ],
        )

    def test_rendered_report_is_unchanged_when_input_order_changes(self) -> None:
        first = self.write_suite(
            "reports/TEST-first.xml", name="first.Suite", tests=1
        )
        second = self.write_suite(
            "reports/TEST-second.xml", name="second.Suite", tests=2
        )

        forward = generate_report.render_report(self.build([first, second]))
        reverse = generate_report.render_report(self.build([second, first]))

        self.assertEqual(forward, reverse)

    def test_parses_testsuites_container(self) -> None:
        report_path = self.root / "aggregate.xml"
        report_path.write_text(
            """<testsuites>
                <testsuite name="suite.B" tests="2" failures="0" errors="0" skipped="1" time="0.2" />
                <testsuite name="suite.A" tests="1" failures="0" errors="0" skipped="0" time="0.1" />
            </testsuites>""",
            encoding="utf-8",
        )

        report = self.build([report_path])

        self.assertEqual(report["totals"]["tests"], 3)
        self.assertEqual([suite["name"] for suite in report["suites"]], ["suite.A", "suite.B"])

    def test_deduplicates_the_same_report_from_file_and_directory_inputs(self) -> None:
        report_path = self.write_suite(
            "reports/TEST-example.xml", name="example.Suite", tests=2
        )

        report = self.build([report_path.parent, report_path])

        self.assertEqual(report["totals"]["tests"], 2)
        self.assertEqual(len(report["suites"]), 1)

    def test_rejects_missing_inputs(self) -> None:
        with self.assertRaisesRegex(
            generate_report.EvidenceError, "no Surefire inputs provided"
        ):
            self.build([])

    def test_rejects_directory_without_reports(self) -> None:
        empty_directory = self.root / "empty"
        empty_directory.mkdir()

        with self.assertRaisesRegex(
            generate_report.EvidenceError, "no Surefire XML reports found"
        ):
            self.build([empty_directory])

    def test_rejects_missing_input_path(self) -> None:
        with self.assertRaisesRegex(
            generate_report.EvidenceError, "Surefire input does not exist"
        ):
            self.build([self.root / "missing"])

    def test_rejects_malformed_xml_with_source_path(self) -> None:
        report_path = self.root / "TEST-broken.xml"
        report_path.write_text("<testsuite>", encoding="utf-8")

        with self.assertRaisesRegex(
            generate_report.EvidenceError,
            "failed to parse Surefire XML 'TEST-broken.xml'",
        ):
            self.build([report_path])

    def test_rejects_inconsistent_suite_counts(self) -> None:
        report_path = self.write_suite(
            "TEST-invalid.xml",
            name="invalid.Suite",
            tests=1,
            failures=1,
            errors=1,
        )

        with self.assertRaisesRegex(
            generate_report.EvidenceError, "inconsistent test counts"
        ):
            self.build([report_path])

    def test_uses_environment_commit_and_source_date_epoch(self) -> None:
        report_path = self.write_suite(
            "TEST-example.xml", name="example.Suite", tests=1
        )

        report = self.build([report_path])

        self.assertEqual(report["git_commit"], "0123456789abcdef")
        self.assertEqual(report["generated_at_utc"], "1970-01-01T00:00:00Z")
        self.assertEqual(report["schema_version"], 1)

    def test_rejects_negative_source_date_epoch(self) -> None:
        report_path = self.write_suite(
            "TEST-example.xml", name="example.Suite", tests=1
        )
        environment = {**self.environment, "SOURCE_DATE_EPOCH": "-1"}

        with self.assertRaisesRegex(
            generate_report.EvidenceError, "invalid SOURCE_DATE_EPOCH"
        ):
            generate_report.build_report(
                project="StockRush",
                scope="order-service",
                inputs=[report_path],
                environ=environment,
                repo_dir=self.root,
            )

    @mock.patch("generate_report.subprocess.run")
    def test_falls_back_to_git_when_commit_environment_is_absent(
        self, run: mock.Mock
    ) -> None:
        run.return_value = mock.Mock(stdout="fedcba9876543210\n")

        commit = generate_report.resolve_git_commit({}, self.root)

        self.assertEqual(commit, "fedcba9876543210")
        run.assert_called_once_with(
            ["git", "-C", str(self.root), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        )

    def test_main_fails_clearly_without_input_and_does_not_write_output(self) -> None:
        output = self.root / "report.json"
        stderr = io.StringIO()
        with mock.patch.dict(os.environ, self.environment, clear=True):
            with redirect_stderr(stderr):
                exit_code = generate_report.main(
                    [
                        "--project",
                        "StockRush",
                        "--scope",
                        "backend-services",
                        "--output",
                        str(output),
                    ]
                )

        self.assertEqual(exit_code, 2)
        self.assertIn("error: no Surefire inputs provided", stderr.getvalue())
        self.assertFalse(output.exists())

    def test_main_writes_valid_json(self) -> None:
        report_path = self.write_suite(
            "TEST-example.xml", name="example.Suite", tests=1
        )
        output = self.root / "output" / "report.json"

        with mock.patch.dict(os.environ, self.environment, clear=True):
            exit_code = generate_report.main(
                [
                    "--project",
                    "StockRush",
                    "--scope",
                    "order-service",
                    "--output",
                    str(output),
                    str(report_path),
                ]
            )

        self.assertEqual(exit_code, 0)
        self.assertEqual(json.loads(output.read_text(encoding="utf-8"))["totals"]["passed"], 1)


if __name__ == "__main__":
    unittest.main()
