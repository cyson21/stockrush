from __future__ import annotations

import importlib.util
import io
import sys
import unittest
from contextlib import redirect_stderr
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "local_e2e_runner.py"
SPEC = importlib.util.spec_from_file_location("local_e2e_runner", MODULE_PATH)
assert SPEC and SPEC.loader
local_e2e_runner = importlib.util.module_from_spec(SPEC)
sys.modules["local_e2e_runner"] = local_e2e_runner
SPEC.loader.exec_module(local_e2e_runner)


class LocalE2ERunnerTest(unittest.TestCase):
    def test_expected_success_count_is_bounded_by_initial_stock(self) -> None:
        self.assertEqual(local_e2e_runner.expected_success_count(5, 1, 8), 5)
        self.assertEqual(local_e2e_runner.expected_success_count(5, 2, 8), 2)
        self.assertEqual(local_e2e_runner.expected_success_count(0, 1, 8), 0)

    def test_summarize_orders_counts_terminal_states(self) -> None:
        orders = [
            {"orderId": "ord-1", "status": "CONFIRMED", "sagaStatus": "COMPLETED"},
            {"orderId": "ord-2", "status": "CANCELLED", "sagaStatus": "FAILED"},
            {"orderId": "ord-3", "status": "CREATED", "sagaStatus": "PAYMENT_REQUESTED"},
        ]

        summary = local_e2e_runner.summarize_orders(orders)

        self.assertEqual(summary.confirmed, 1)
        self.assertEqual(summary.cancelled, 1)
        self.assertEqual(summary.unresolved_order_ids, ["ord-3"])

    def test_validate_concurrent_sku_result_accepts_expected_no_oversell_state(self) -> None:
        orders = [
            {"orderId": "ord-1", "status": "CONFIRMED", "sagaStatus": "COMPLETED"},
            {"orderId": "ord-2", "status": "CONFIRMED", "sagaStatus": "COMPLETED"},
            {"orderId": "ord-3", "status": "CANCELLED", "sagaStatus": "FAILED"},
        ]
        stock = {"availableQuantity": 0, "reservedQuantity": 0}
        pending = {"order": 0, "inventory": 0, "payment": 0}

        errors = local_e2e_runner.validate_concurrent_sku_result(
            orders=orders,
            stock=stock,
            initial_stock=2,
            quantity_per_order=1,
            order_count=3,
            pending_outbox_counts=pending,
        )

        self.assertEqual(errors, [])

    def test_validate_concurrent_sku_result_reports_oversell_and_pending_outbox(self) -> None:
        orders = [
            {"orderId": "ord-1", "status": "CONFIRMED", "sagaStatus": "COMPLETED"},
            {"orderId": "ord-2", "status": "CONFIRMED", "sagaStatus": "COMPLETED"},
            {"orderId": "ord-3", "status": "CONFIRMED", "sagaStatus": "COMPLETED"},
        ]
        stock = {"availableQuantity": -1, "reservedQuantity": 1}
        pending = {"order": 1, "inventory": 0, "payment": 0}

        errors = local_e2e_runner.validate_concurrent_sku_result(
            orders=orders,
            stock=stock,
            initial_stock=2,
            quantity_per_order=1,
            order_count=3,
            pending_outbox_counts=pending,
        )

        self.assertTrue(any("confirmed count" in error for error in errors))
        self.assertTrue(any("stock" in error for error in errors))
        self.assertTrue(any("pending outbox" in error for error in errors))

    def test_pending_outbox_delta_ignores_pre_existing_rows(self) -> None:
        before = {"order": 2, "inventory": 1, "payment": 0}
        after = {"order": 2, "inventory": 3, "payment": 0}

        delta = local_e2e_runner.pending_outbox_delta(before, after)

        self.assertEqual(delta, {"order": 0, "inventory": 2, "payment": 0})

    def test_cli_accepts_same_sku_concurrency_command_name(self) -> None:
        args = local_e2e_runner.parse_args(["same-sku-concurrency"])

        self.assertEqual(args.command, "same-sku-concurrency")
        self.assertEqual(args.order_url, "http://localhost:18083")
        self.assertEqual(args.order_api_url, "http://localhost:18080")

    def test_config_from_args_separates_order_admin_and_public_api_urls(self) -> None:
        args = local_e2e_runner.parse_args([
            "same-sku-concurrency",
            "--order-url",
            "http://order-admin.local/",
            "--order-api-url",
            "http://gateway.local/",
        ])

        config = local_e2e_runner.config_from_args(args)

        self.assertEqual(config.order_url, "http://order-admin.local")
        self.assertEqual(config.order_api_url, "http://gateway.local")

    def test_cli_rejects_invalid_numeric_arguments(self) -> None:
        invalid_args = [
            ["same-sku-concurrency", "--orders", "0"],
            ["same-sku-concurrency", "--initial-stock", "-1"],
            ["same-sku-concurrency", "--quantity", "0"],
            ["same-sku-concurrency", "--max-attempts", "0"],
            ["same-sku-concurrency", "--relay-batch-size", "101"],
            ["same-sku-concurrency", "--wait-seconds", "-0.1"],
            ["same-sku-concurrency", "--prefix", " "],
            ["same-sku-concurrency", "--prefix", "X" * 49],
        ]

        for argv in invalid_args:
            with self.subTest(argv=argv):
                with redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit):
                        local_e2e_runner.parse_args(argv)

    def test_scenario_ids_include_unique_suffix_and_stay_within_db_lengths(self) -> None:
        product_code, sku_id = local_e2e_runner.scenario_ids("CONC-E2E")

        self.assertRegex(product_code, r"^CONC-E2E-\d{14}-[a-f0-9]{8}$")
        self.assertEqual(sku_id, f"{product_code}-S")
        self.assertLessEqual(len(product_code), 80)
        self.assertLessEqual(len(sku_id), 80)


if __name__ == "__main__":
    unittest.main()
