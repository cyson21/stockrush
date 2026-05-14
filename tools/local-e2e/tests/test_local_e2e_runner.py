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

    def test_demo_coupon_amounts_account_for_quantity(self) -> None:
        args = local_e2e_runner.parse_args(["demo-order-flow", "--unit-price", "12000", "--quantity", "2"])
        config = local_e2e_runner.config_from_args(args)

        self.assertEqual(local_e2e_runner.demo_order_amount(config), 24000)
        self.assertEqual(local_e2e_runner.demo_coupon_discount_amount(config), 1000)
        self.assertEqual(local_e2e_runner.demo_coupon_payable_amount(config), 23000)

    def test_demo_coupon_period_is_relative_to_runtime(self) -> None:
        now = local_e2e_runner.datetime(2031, 1, 2, 3, 4, 5, tzinfo=local_e2e_runner.timezone.utc)

        starts_at, ends_at = local_e2e_runner.demo_coupon_period(now)

        self.assertEqual(starts_at, "2031-01-01T03:04:05Z")
        self.assertEqual(ends_at, "2032-01-02T03:04:05Z")

    def test_validate_demo_order_flow_result_accepts_expected_states(self) -> None:
        pending = {"order": 0, "inventory": 0, "payment": 0}

        errors = local_e2e_runner.validate_demo_order_flow_result(
            card_order={
                "orderId": "ord-card",
                "status": "CONFIRMED",
                "sagaStatus": "COMPLETED",
                "couponCode": "DEMO-COUPON",
                "discountAmount": 1000,
                "payableAmount": 11000,
            },
            fail_order={"orderId": "ord-fail", "status": "CANCELLED", "sagaStatus": "FAILED"},
            delay_order={"orderId": "ord-delay", "status": "CANCELLED", "sagaStatus": "FAILED"},
            stock={"availableQuantity": 19, "reservedQuantity": 0},
            initial_stock=20,
            quantity_per_order=1,
            pending_outbox_counts=pending,
            coupon_code="DEMO-COUPON",
            expected_discount_amount=1000,
            expected_payable_amount=11000,
        )

        self.assertEqual(errors, [])

    def test_validate_demo_order_flow_result_reports_coupon_mismatch(self) -> None:
        pending = {"order": 0, "inventory": 0, "payment": 0}

        errors = local_e2e_runner.validate_demo_order_flow_result(
            card_order={
                "orderId": "ord-card",
                "status": "CONFIRMED",
                "sagaStatus": "COMPLETED",
                "couponCode": None,
                "discountAmount": 0,
                "payableAmount": 12000,
            },
            fail_order={"orderId": "ord-fail", "status": "CANCELLED", "sagaStatus": "FAILED"},
            delay_order={"orderId": "ord-delay", "status": "CANCELLED", "sagaStatus": "FAILED"},
            stock={"availableQuantity": 19, "reservedQuantity": 0},
            initial_stock=20,
            quantity_per_order=1,
            pending_outbox_counts=pending,
            coupon_code="DEMO-COUPON",
            expected_discount_amount=1000,
            expected_payable_amount=11000,
        )

        self.assertTrue(any("coupon" in error for error in errors))
        self.assertTrue(any("discountAmount" in error for error in errors))
        self.assertTrue(any("payableAmount" in error for error in errors))

    def test_validate_coupon_quote_result_accepts_expected_quote(self) -> None:
        errors = local_e2e_runner.validate_coupon_quote_result(
            quote={
                "couponCode": "DEMO-COUPON",
                "applied": True,
                "discountAmount": 1000,
                "payAmount": 23000,
                "reason": "APPLIED",
            },
            coupon_code="DEMO-COUPON",
            expected_discount_amount=1000,
            expected_payable_amount=23000,
        )

        self.assertEqual(errors, [])

    def test_validate_coupon_quote_result_reports_mismatch(self) -> None:
        errors = local_e2e_runner.validate_coupon_quote_result(
            quote={
                "couponCode": "OTHER",
                "applied": False,
                "discountAmount": 0,
                "payAmount": 24000,
                "reason": "COUPON_NOT_ACTIVE",
            },
            coupon_code="DEMO-COUPON",
            expected_discount_amount=1000,
            expected_payable_amount=23000,
        )

        self.assertTrue(any("quote coupon" in error for error in errors))
        self.assertTrue(any("quote applied" in error for error in errors))
        self.assertTrue(any("quote discountAmount" in error for error in errors))
        self.assertTrue(any("quote payAmount" in error for error in errors))

    def test_validate_demo_order_flow_result_reports_unfinished_flow(self) -> None:
        pending = {"order": 0, "inventory": 1, "payment": 0}

        errors = local_e2e_runner.validate_demo_order_flow_result(
            card_order={"orderId": "ord-card", "status": "CREATED", "sagaStatus": "PAYMENT_REQUESTED"},
            fail_order={"orderId": "ord-fail", "status": "CANCELLED", "sagaStatus": "FAILED"},
            delay_order={"orderId": "ord-delay", "status": "CREATED", "sagaStatus": "PAYMENT_DELAYED"},
            stock={"availableQuantity": 18, "reservedQuantity": 1},
            initial_stock=20,
            quantity_per_order=1,
            pending_outbox_counts=pending,
            coupon_code="DEMO-COUPON",
            expected_discount_amount=1000,
            expected_payable_amount=11000,
        )

        self.assertTrue(any("CARD" in error for error in errors))
        self.assertTrue(any("DELAY_CARD" in error for error in errors))
        self.assertTrue(any("stock" in error for error in errors))
        self.assertTrue(any("pending outbox" in error for error in errors))

    def test_cli_accepts_same_sku_concurrency_command_name(self) -> None:
        args = local_e2e_runner.parse_args(["same-sku-concurrency"])

        self.assertEqual(args.command, "same-sku-concurrency")
        self.assertEqual(args.order_url, "http://localhost:18083")
        self.assertEqual(args.order_api_url, "http://localhost:18080")
        self.assertEqual(args.outbox_api_url, "http://localhost:18080")

    def test_cli_accepts_demo_order_flow_command_name(self) -> None:
        args = local_e2e_runner.parse_args(["demo-order-flow"])

        self.assertEqual(args.command, "demo-order-flow")
        self.assertEqual(args.orders, 3)
        self.assertEqual(args.initial_stock, 20)
        self.assertEqual(args.prefix, "DEMO-E2E")
        self.assertEqual(args.promotion_url, "http://localhost:18085")

    def test_config_from_args_separates_order_admin_public_and_outbox_api_urls(self) -> None:
        args = local_e2e_runner.parse_args([
            "same-sku-concurrency",
            "--order-url",
            "http://order-admin.local/",
            "--order-api-url",
            "http://gateway.local/",
            "--outbox-api-url",
            "http://gateway-outbox.local/",
        ])

        config = local_e2e_runner.config_from_args(args)

        self.assertEqual(config.order_url, "http://order-admin.local")
        self.assertEqual(config.order_api_url, "http://gateway.local")
        self.assertEqual(config.outbox_api_url, "http://gateway-outbox.local")

    def test_outbox_urls_use_gateway_service_routes(self) -> None:
        args = local_e2e_runner.parse_args([
            "same-sku-concurrency",
            "--outbox-api-url",
            "http://gateway.local/",
        ])
        config = local_e2e_runner.config_from_args(args)

        self.assertEqual(
            local_e2e_runner.outbox_list_url(config, "inventory"),
            "http://gateway.local/api/admin/outbox-services/inventory/events?status=PENDING&limit=200",
        )
        self.assertEqual(
            local_e2e_runner.outbox_retry_url(config, "payment"),
            "http://gateway.local/api/admin/outbox-services/payment/events/retry?batchSize=100",
        )

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
