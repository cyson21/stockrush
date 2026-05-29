from __future__ import annotations

import importlib.util
import io
import json
import os
import sys
import unittest
from contextlib import redirect_stderr
from pathlib import Path

# 동시 주문/멱등성 시나리오의 경계 동작을 검사하는 회귀 테스트 집합입니다.


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

    def test_order_ids_by_request_index_groups_idempotency_replays(self) -> None:
        attempts = [
            {"index": 1, "replay": 1, "orderId": "ord-1"},
            {"index": 1, "replay": 2, "orderId": "ord-1"},
            {"index": 2, "replay": 1, "orderId": "ord-2"},
        ]

        grouped = local_e2e_runner.order_ids_by_request_index(attempts)

        self.assertEqual(grouped, {1: ["ord-1", "ord-1"], 2: ["ord-2"]})

    def test_unique_orders_from_attempts_keeps_one_order_per_request_index(self) -> None:
        attempts = [
            {"index": 2, "replay": 2, "order": {"orderId": "ord-2"}},
            {"index": 1, "replay": 1, "order": {"orderId": "ord-1"}},
            {"index": 2, "replay": 1, "order": {"orderId": "ord-2"}},
        ]

        orders = local_e2e_runner.unique_orders_from_attempts(attempts)

        self.assertEqual([order["orderId"] for order in orders], ["ord-1", "ord-2"])

    def test_validate_burst_idempotency_result_accepts_expected_stable_state(self) -> None:
        orders = [
            {"orderId": "ord-1", "status": "CONFIRMED", "sagaStatus": "COMPLETED"},
            {"orderId": "ord-2", "status": "CONFIRMED", "sagaStatus": "COMPLETED"},
            {"orderId": "ord-3", "status": "CANCELLED", "sagaStatus": "FAILED"},
            {"orderId": "ord-4", "status": "CANCELLED", "sagaStatus": "FAILED"},
        ]
        stock = {"availableQuantity": 0, "reservedQuantity": 0}
        pending = {"order": 0, "inventory": 0, "payment": 0}

        errors = local_e2e_runner.validate_burst_idempotency_result(
            orders=orders,
            stock=stock,
            initial_stock=2,
            quantity_per_order=1,
            order_count=4,
            pending_outbox_counts=pending,
            replay_order_ids_by_index={
                1: ["ord-1", "ord-1"],
                2: ["ord-2", "ord-2"],
                3: ["ord-3", "ord-3"],
                4: ["ord-4", "ord-4"],
            },
            request_attempt_count=8,
            idempotency_replays=2,
            post_replay_orders=orders,
            post_replay_stock=stock,
            post_replay_pending_outbox_counts=pending,
        )

        self.assertEqual(errors, [])

    def test_validate_burst_idempotency_result_reports_replay_and_post_relay_drift(self) -> None:
        orders = [
            {"orderId": "ord-1", "status": "CONFIRMED", "sagaStatus": "COMPLETED"},
            {"orderId": "ord-2", "status": "CANCELLED", "sagaStatus": "FAILED"},
        ]
        post_replay_orders = [
            {"orderId": "ord-1", "status": "CONFIRMED", "sagaStatus": "COMPLETED"},
            {"orderId": "ord-2", "status": "CONFIRMED", "sagaStatus": "COMPLETED"},
        ]

        errors = local_e2e_runner.validate_burst_idempotency_result(
            orders=orders,
            stock={"availableQuantity": 0, "reservedQuantity": 0},
            initial_stock=1,
            quantity_per_order=1,
            order_count=2,
            pending_outbox_counts={"order": 0, "inventory": 0, "payment": 0},
            replay_order_ids_by_index={1: ["ord-1", "ord-1b"], 2: ["ord-2", "ord-2"]},
            request_attempt_count=3,
            idempotency_replays=2,
            post_replay_orders=post_replay_orders,
            post_replay_stock={"availableQuantity": -1, "reservedQuantity": 0},
            post_replay_pending_outbox_counts={"order": 1, "inventory": 0, "payment": 0},
        )

        self.assertTrue(any("idempotency replay" in error for error in errors))
        self.assertTrue(any("request attempt count" in error for error in errors))
        self.assertTrue(any("post-replay summary drift" in error for error in errors))
        self.assertTrue(any("post-replay stock drift" in error for error in errors))
        self.assertTrue(any("post-replay pending outbox" in error for error in errors))

    def test_pending_outbox_delta_reports_signed_changes(self) -> None:
        before = {"order": 2, "inventory": 1, "payment": 0}
        after = {"order": 1, "inventory": 3, "payment": 0}

        delta = local_e2e_runner.pending_outbox_delta(before, after)

        self.assertEqual(delta, {"order": -1, "inventory": 2, "payment": 0})

    def test_positive_pending_outbox_changes_ignores_decreases_for_failure_check(self) -> None:
        changes = {"order": -1, "inventory": 2, "payment": 0}

        pending = local_e2e_runner.positive_pending_outbox_changes(changes)

        self.assertEqual(pending, {"inventory": 2})

    def test_new_pending_outbox_event_ids_reports_after_only_ids(self) -> None:
        before = {"order": {"old-order"}, "inventory": set(), "payment": {"old-payment"}}
        after = {"order": {"old-order", "new-order"}, "inventory": {"new-inventory"}}

        new_event_ids = local_e2e_runner.new_pending_outbox_event_ids(before, after)

        self.assertEqual(new_event_ids, {"order": ["new-order"], "inventory": ["new-inventory"]})

    def test_validate_burst_idempotency_result_reports_new_pending_outbox_ids(self) -> None:
        orders = [
            {"orderId": "ord-1", "status": "CONFIRMED", "sagaStatus": "COMPLETED"},
            {"orderId": "ord-2", "status": "CANCELLED", "sagaStatus": "FAILED"},
        ]

        errors = local_e2e_runner.validate_burst_idempotency_result(
            orders=orders,
            stock={"availableQuantity": 0, "reservedQuantity": 0},
            initial_stock=1,
            quantity_per_order=1,
            order_count=2,
            pending_outbox_counts={"order": 0, "inventory": 0, "payment": 0},
            new_pending_outbox_event_ids={"order": ["new-order-event"]},
            replay_order_ids_by_index={1: ["ord-1", "ord-1"], 2: ["ord-2", "ord-2"]},
            request_attempt_count=4,
            idempotency_replays=2,
            post_replay_orders=orders,
            post_replay_stock={"availableQuantity": 0, "reservedQuantity": 0},
            post_replay_pending_outbox_counts={"order": 0, "inventory": 0, "payment": 0},
            post_replay_new_pending_outbox_event_ids={"payment": ["new-payment-event"]},
        )

        self.assertTrue(any("new pending outbox events" in error for error in errors))
        self.assertTrue(any("post-replay new pending outbox events" in error for error in errors))

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
        self.assertEqual(args.relay_mode, "manual")

    def test_cli_accepts_demo_order_flow_command_name(self) -> None:
        args = local_e2e_runner.parse_args(["demo-order-flow", "--relay-mode", "automatic"])

        self.assertEqual(args.command, "demo-order-flow")
        self.assertEqual(args.orders, 3)
        self.assertEqual(args.initial_stock, 20)
        self.assertEqual(args.prefix, "DEMO-E2E")
        self.assertEqual(args.promotion_url, "http://localhost:18085")
        self.assertEqual(args.relay_mode, "automatic")

    def test_cli_accepts_burst_idempotency_command_name(self) -> None:
        args = local_e2e_runner.parse_args(["burst-idempotency"])

        self.assertEqual(args.command, "burst-idempotency")
        self.assertEqual(args.orders, 30)
        self.assertEqual(args.initial_stock, 10)
        self.assertEqual(args.prefix, "BURST-E2E")
        self.assertEqual(args.idempotency_replays, 2)
        self.assertEqual(args.relay_workers, 4)
        self.assertEqual(args.stability_waves, 2)

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

    def test_outbox_list_url_accepts_multiple_statuses_for_recovery(self) -> None:
        args = local_e2e_runner.parse_args([
            "outbox-recovery",
            "--outbox-api-url",
            "http://gateway.local/",
        ])
        config = local_e2e_runner.config_from_args(args)

        self.assertEqual(
            local_e2e_runner.outbox_list_url(config, "order", statuses=("PENDING", "FAILED")),
            "http://gateway.local/api/admin/outbox-services/order/events?status=PENDING,FAILED&limit=200",
        )
        self.assertEqual(
            local_e2e_runner.outbox_requeue_failed_url(config, "inventory"),
            "http://gateway.local/api/admin/outbox-services/inventory/events/failed/requeue?batchSize=100",
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
            ["burst-idempotency", "--idempotency-replays", "0"],
            ["burst-idempotency", "--relay-workers", "0"],
            ["burst-idempotency", "--stability-waves", "0"],
            ["demo-order-flow", "--relay-mode", "external"],
            ["outbox-recovery", "--operator-id", " "],
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

    def test_maybe_relay_wave_uses_manual_retry_when_configured(self) -> None:
        args = local_e2e_runner.parse_args(["demo-order-flow", "--relay-mode", "manual"])
        config = local_e2e_runner.config_from_args(args)
        calls: list[str] = []
        original = local_e2e_runner.relay_wave
        local_e2e_runner.relay_wave = lambda _client, _config: calls.append("manual")
        try:
            local_e2e_runner.maybe_relay_wave(object(), config)
        finally:
            local_e2e_runner.relay_wave = original

        self.assertEqual(calls, ["manual"])

    def test_maybe_relay_wave_skips_retry_when_automatic_relay_is_configured(self) -> None:
        args = local_e2e_runner.parse_args(["demo-order-flow", "--relay-mode", "automatic"])
        config = local_e2e_runner.config_from_args(args)
        calls: list[str] = []
        original = local_e2e_runner.relay_wave
        local_e2e_runner.relay_wave = lambda _client, _config: calls.append("manual")
        try:
            local_e2e_runner.maybe_relay_wave(object(), config)
        finally:
            local_e2e_runner.relay_wave = original

        self.assertEqual(calls, [])

    def test_cli_accepts_outbox_recovery_command_name(self) -> None:
        args = local_e2e_runner.parse_args(["outbox-recovery", "--operator-id", "qa-runner"])

        self.assertEqual(args.command, "outbox-recovery")
        self.assertEqual(args.outbox_api_url, "http://localhost:18080")
        self.assertEqual(args.operator_id, "qa-runner")
        self.assertFalse(args.skip_requeue_failed)

    def test_cli_accepts_admin_bearer_token_option(self) -> None:
        args = local_e2e_runner.parse_args([
            "demo-order-flow",
            "--admin-bearer-token",
            "Bearer token-abc",
        ])

        self.assertEqual(args.admin_bearer_token, "Bearer token-abc")

    def test_cli_defaults_admin_bearer_token_from_environment(self) -> None:
        original = os.environ.get("STOCKRUSH_ADMIN_BEARER_TOKEN")
        try:
            os.environ["STOCKRUSH_ADMIN_BEARER_TOKEN"] = "env-token-xyz"
            args = local_e2e_runner.parse_args(["demo-order-flow"])

            self.assertEqual(args.admin_bearer_token, "env-token-xyz")
        finally:
            if original is None:
                del os.environ["STOCKRUSH_ADMIN_BEARER_TOKEN"]
            else:
                os.environ["STOCKRUSH_ADMIN_BEARER_TOKEN"] = original

    def test_admin_bearer_token_is_normalized_in_config(self) -> None:
        args = local_e2e_runner.parse_args([
            "demo-order-flow",
            "--admin-bearer-token",
            "  Bearer normalize-token  ",
        ])
        config = local_e2e_runner.config_from_args(args)

        self.assertEqual(config.admin_bearer_token, "normalize-token")

    def test_admin_auth_headers_adds_bearer_prefix(self) -> None:
        args = local_e2e_runner.parse_args(["demo-order-flow", "--admin-bearer-token", "normalize-token"])
        config = local_e2e_runner.config_from_args(args)

        self.assertEqual(
            local_e2e_runner.admin_auth_headers(config),
            {"Authorization": "Bearer normalize-token"},
        )

    def test_admin_auth_headers_without_token_returns_empty(self) -> None:
        args = local_e2e_runner.parse_args(["demo-order-flow"])
        config = local_e2e_runner.config_from_args(args)

        self.assertEqual(local_e2e_runner.admin_auth_headers(config), {})

    def test_customer_auth_headers_adds_bearer_prefix(self) -> None:
        args = local_e2e_runner.parse_args(["demo-order-flow", "--customer-bearer-token", "customer-token"])
        config = local_e2e_runner.config_from_args(args)

        self.assertEqual(
            local_e2e_runner.customer_auth_headers(config),
            {"Authorization": "Bearer customer-token"},
        )

    def test_api_client_get_accepts_headers(self) -> None:
        captured: dict[str, object] = {}

        class FakeResponse:
            def __enter__(self) -> "FakeResponse":
                return self

            def __exit__(self, *_args: object) -> None:
                return None

            def read(self) -> bytes:
                return b'{"ok": true}'

        original_urlopen = local_e2e_runner.urllib.request.urlopen
        try:
            def fake_urlopen(request: object, timeout: float) -> FakeResponse:
                captured["timeout"] = timeout
                captured["request"] = request
                return FakeResponse()

            local_e2e_runner.urllib.request.urlopen = fake_urlopen
            client = local_e2e_runner.ApiClient(timeout_seconds=1.5)

            payload = client.get("http://gateway.local/api/admin/outbox", headers={"Authorization": "Bearer t1"})
        finally:
            local_e2e_runner.urllib.request.urlopen = original_urlopen

        request = captured["request"]
        self.assertEqual(payload, {"ok": True})
        self.assertEqual(captured["timeout"], 1.5)
        self.assertEqual(request.get_header("Authorization"), "Bearer t1")

    def test_cli_accepts_kafka_outage_recovery_command_name(self) -> None:
        args = local_e2e_runner.parse_args([
            "kafka-outage-recovery",
            "--compose-file",
            "infra/demo/docker-compose.yml",
            "--env-file",
            "infra/demo/.env",
            "--relay-mode",
            "automatic",
        ])

        self.assertEqual(args.command, "kafka-outage-recovery")
        self.assertEqual(args.compose_file, "infra/demo/docker-compose.yml")
        self.assertEqual(args.env_file, "infra/demo/.env")
        self.assertEqual(args.kafka_service, "kafka")
        self.assertEqual(args.prefix, "KAFKA-OUTAGE-E2E")
        self.assertEqual(args.orders, 1)
        self.assertEqual(args.initial_stock, 3)
        self.assertEqual(args.relay_mode, "automatic")

    def test_count_outbox_attaches_admin_bearer_token(self) -> None:
        args = local_e2e_runner.parse_args([
            "outbox-recovery",
            "--admin-bearer-token",
            "token-456",
        ])
        config = local_e2e_runner.config_from_args(args)
        captured_headers: list[dict[str, str]] = []

        class FakeClient:
            def get(self, _url: str, headers: Mapping[str, str] | None = None) -> dict[str, list[object]]:
                captured_headers.append(dict(headers or {}))
                return {"items": []}

        counts = local_e2e_runner.count_outbox(FakeClient(), config, statuses=("PENDING",))

        self.assertEqual(counts, {"order": 0, "inventory": 0, "payment": 0})
        self.assertEqual(len(captured_headers), 3)
        for call_headers in captured_headers:
            self.assertEqual(call_headers["Authorization"], "Bearer token-456")

    def test_outbox_items_attaches_admin_bearer_token(self) -> None:
        args = local_e2e_runner.parse_args([
            "outbox-recovery",
            "--admin-bearer-token",
            "token-789",
        ])
        config = local_e2e_runner.config_from_args(args)
        captured_headers: list[dict[str, str]] = []

        class FakeClient:
            def get(self, _url: str, headers: Mapping[str, str] | None = None) -> dict[str, list[object]]:
                captured_headers.append(dict(headers or {}))
                return {"items": []}

        items = local_e2e_runner.outbox_items(FakeClient(), config, "order", statuses=("PENDING",))

        self.assertEqual(items, [])
        self.assertEqual(len(captured_headers), 1)
        self.assertEqual(captured_headers[0]["Authorization"], "Bearer token-789")

    def test_relay_wave_attaches_admin_bearer_token(self) -> None:
        args = local_e2e_runner.parse_args([
            "same-sku-concurrency",
            "--admin-bearer-token",
            "retry-token",
            "--wait-seconds",
            "0",
        ])
        config = local_e2e_runner.config_from_args(args)
        posted_headers: list[dict[str, str]] = []

        class FakeClient:
            def post(
                self,
                _url: str,
                body: Mapping[str, Any] | None = None,
                headers: Mapping[str, str] | None = None,
            ) -> dict[str, str]:
                posted_headers.append(dict(headers or {}))
                return {}

        local_e2e_runner.relay_wave(FakeClient(), config)

        self.assertEqual(len(posted_headers), 6)
        for headers in posted_headers:
            self.assertEqual(headers["Authorization"], "Bearer retry-token")

    def test_cancel_delayed_order_attaches_admin_bearer_token(self) -> None:
        args = local_e2e_runner.parse_args([
            "demo-order-flow",
            "--admin-bearer-token",
            "cancel-token",
        ])
        config = local_e2e_runner.config_from_args(args)
        posted_headers: list[dict[str, str]] = []

        class FakeClient:
            def post(
                self,
                _url: str,
                body: Mapping[str, object] | None = None,
                headers: Mapping[str, str] | None = None,
            ) -> dict[str, object]:
                posted_headers.append(dict(headers or {}))
                return {}

        local_e2e_runner.cancel_delayed_order(FakeClient(), config, "order-1", "sku-1")

        self.assertEqual(len(posted_headers), 1)
        self.assertEqual(posted_headers[0]["Authorization"], "Bearer cancel-token")
        self.assertEqual(posted_headers[0]["Idempotency-Key"], "idem-admin-cancel-sku-1-order-1")

    def test_create_order_attaches_customer_bearer_token(self) -> None:
        args = local_e2e_runner.parse_args([
            "demo-order-flow",
            "--customer-bearer-token",
            "customer-create-token",
        ])
        config = local_e2e_runner.config_from_args(args)
        posted_headers: list[dict[str, str]] = []

        class FakeClient:
            def post(
                self,
                _url: str,
                body: Mapping[str, object] | None = None,
                headers: Mapping[str, str] | None = None,
            ) -> dict[str, object]:
                posted_headers.append(dict(headers or {}))
                return {"orderId": "ord-test"}

        local_e2e_runner.create_order(FakeClient(), config, "product-1", "sku-1", "CARD", "member-1", 1)

        self.assertEqual(len(posted_headers), 1)
        self.assertEqual(posted_headers[0]["Authorization"], "Bearer customer-create-token")

    def test_get_order_attaches_customer_bearer_token(self) -> None:
        args = local_e2e_runner.parse_args([
            "demo-order-flow",
            "--customer-bearer-token",
            "customer-get-token",
        ])
        config = local_e2e_runner.config_from_args(args)
        captured_headers: list[dict[str, str]] = []

        class FakeClient:
            def get(self, _url: str, headers: Mapping[str, str] | None = None) -> dict[str, object]:
                captured_headers.append(dict(headers or {}))
                return {"data": {"orderId": "ord-test"}}

        order = local_e2e_runner.get_order(FakeClient(), config, "ord-test")

        self.assertEqual(order, {"orderId": "ord-test"})
        self.assertEqual(captured_headers[0]["Authorization"], "Bearer customer-get-token")

    def test_docker_compose_action_pauses_named_kafka_service(self) -> None:
        args = local_e2e_runner.parse_args([
            "kafka-outage-recovery",
            "--compose-file",
            "infra/demo/docker-compose.yml",
            "--env-file",
            "infra/demo/.env",
            "--kafka-service",
            "broker",
        ])
        config = local_e2e_runner.config_from_args(args)
        calls: list[list[str]] = []

        def fake_run(command: list[str], **_kwargs: object) -> object:
            calls.append(command)

            class Result:
                stdout = ""
                stderr = ""

            return Result()

        original_run = local_e2e_runner.subprocess.run
        local_e2e_runner.subprocess.run = fake_run
        try:
            local_e2e_runner.run_docker_compose_action(config, "pause")
        finally:
            local_e2e_runner.subprocess.run = original_run

        self.assertEqual(
            calls,
            [[
                "docker",
                "compose",
                "--env-file",
                "infra/demo/.env",
                "-f",
                "infra/demo/docker-compose.yml",
                "pause",
                "broker",
            ]],
        )

    def test_validate_kafka_outage_recovery_result_accepts_pause_then_recovery(self) -> None:
        errors = local_e2e_runner.validate_kafka_outage_recovery_result(
            paused_order={"orderId": "ord-1", "status": "CREATED", "sagaStatus": "PAYMENT_REQUESTED"},
            paused_pending_outbox_counts={"order": 1, "inventory": 0, "payment": 0},
            paused_new_pending_outbox_event_ids={"order": ["evt-1"]},
            final_order={"orderId": "ord-1", "status": "CONFIRMED", "sagaStatus": "COMPLETED"},
            final_stock={"availableQuantity": 2, "reservedQuantity": 0},
            initial_stock=3,
            quantity_per_order=1,
            final_pending_outbox_counts={"order": 0, "inventory": 0, "payment": 0},
            final_new_pending_outbox_event_ids={},
        )

        self.assertEqual(errors, [])

    def test_validate_kafka_outage_recovery_result_reports_unobserved_or_unrecovered_state(self) -> None:
        errors = local_e2e_runner.validate_kafka_outage_recovery_result(
            paused_order={"orderId": "ord-1", "status": "CONFIRMED", "sagaStatus": "COMPLETED"},
            paused_pending_outbox_counts={"order": 0, "inventory": 0, "payment": 0},
            paused_new_pending_outbox_event_ids={},
            final_order={"orderId": "ord-1", "status": "CREATED", "sagaStatus": "PAYMENT_REQUESTED"},
            final_stock={"availableQuantity": 3, "reservedQuantity": 1},
            initial_stock=3,
            quantity_per_order=1,
            final_pending_outbox_counts={"order": 1, "inventory": 0, "payment": 0},
            final_new_pending_outbox_event_ids={"order": ["evt-1"]},
        )

        self.assertTrue(any("settled while kafka was paused" in error for error in errors))
        self.assertTrue(any("pending outbox was not observed" in error for error in errors))
        self.assertTrue(any("final order" in error for error in errors))
        self.assertTrue(any("stock" in error for error in errors))
        self.assertTrue(any("final pending outbox" in error for error in errors))
        self.assertTrue(any("final new pending outbox events" in error for error in errors))

    def test_kafka_outage_observation_statuses_include_publishing_rows(self) -> None:
        self.assertEqual(
            local_e2e_runner.kafka_outage_observation_statuses(),
            ("PENDING", "PUBLISHING", "FAILED"),
        )

    def test_observe_kafka_outage_state_polls_until_outbox_is_visible(self) -> None:
        args = local_e2e_runner.parse_args([
            "kafka-outage-recovery",
            "--outage-observation-seconds",
            "0",
            "--wait-seconds",
            "0",
        ])
        config = local_e2e_runner.config_from_args(args)

        class FakeClient:
            pass

        order_calls: list[str] = []
        count_calls: list[str] = []
        id_calls: list[str] = []

        original_get_order = local_e2e_runner.get_order
        original_count_outbox = local_e2e_runner.count_outbox
        original_outbox_event_ids = local_e2e_runner.outbox_event_ids
        try:
            local_e2e_runner.get_order = lambda _client, _config, order_id: (
                order_calls.append(order_id)
                or {"orderId": order_id, "status": "CREATED", "sagaStatus": "STARTED"}
            )
            local_e2e_runner.count_outbox = lambda _client, _config, statuses: (
                count_calls.append(",".join(statuses))
                or (
                    {"order": 0, "inventory": 0, "payment": 0}
                    if len(count_calls) == 1
                    else {"order": 1, "inventory": 0, "payment": 0}
                )
            )
            local_e2e_runner.outbox_event_ids = lambda _client, _config, statuses: (
                id_calls.append(",".join(statuses))
                or (
                    {"order": set(), "inventory": set(), "payment": set()}
                    if len(id_calls) == 1
                    else {"order": {"evt-1"}, "inventory": set(), "payment": set()}
                )
            )

            observed = local_e2e_runner.observe_kafka_outage_state(
                FakeClient(),
                config,
                "ord-1",
                {"order": 0, "inventory": 0, "payment": 0},
                {"order": set(), "inventory": set(), "payment": set()},
            )
        finally:
            local_e2e_runner.get_order = original_get_order
            local_e2e_runner.count_outbox = original_count_outbox
            local_e2e_runner.outbox_event_ids = original_outbox_event_ids

        self.assertEqual(order_calls, ["ord-1", "ord-1"])
        self.assertEqual(observed["pendingOutboxDelta"], {"order": 1, "inventory": 0, "payment": 0})
        self.assertEqual(observed["newPendingOutboxEventIds"], {"order": ["evt-1"]})

    def test_kafka_outage_scenario_reports_missing_order_id_without_key_error(self) -> None:
        args = local_e2e_runner.parse_args(["kafka-outage-recovery"])
        config = local_e2e_runner.config_from_args(args)

        original_healthcheck = local_e2e_runner.healthcheck
        original_ensure_no_pending_outbox = local_e2e_runner.ensure_no_pending_outbox
        original_count_outbox = local_e2e_runner.count_outbox
        original_outbox_event_ids = local_e2e_runner.outbox_event_ids
        original_seed_product = local_e2e_runner.seed_product
        original_seed_stock = local_e2e_runner.seed_stock
        original_run_docker_compose_action = local_e2e_runner.run_docker_compose_action
        original_create_order = local_e2e_runner.create_order

        actions: list[str] = []
        try:
            local_e2e_runner.healthcheck = lambda _client, _config: None
            local_e2e_runner.ensure_no_pending_outbox = lambda _client, _config: None
            local_e2e_runner.count_outbox = lambda _client, _config, statuses: {"order": 0, "inventory": 0, "payment": 0}
            local_e2e_runner.outbox_event_ids = lambda _client, _config, statuses: {"order": set(), "inventory": set(), "payment": set()}
            local_e2e_runner.seed_product = lambda _client, _config, _product_code: None
            local_e2e_runner.seed_stock = lambda _client, _config, _product_code, _sku_id: None
            local_e2e_runner.run_docker_compose_action = lambda _config, action: actions.append(action)
            local_e2e_runner.create_order = lambda *_args, **_kwargs: {}

            with self.assertRaisesRegex(RuntimeError, "order response did not include orderId"):
                local_e2e_runner.run_kafka_outage_recovery_scenario(config)
        finally:
            local_e2e_runner.healthcheck = original_healthcheck
            local_e2e_runner.ensure_no_pending_outbox = original_ensure_no_pending_outbox
            local_e2e_runner.count_outbox = original_count_outbox
            local_e2e_runner.outbox_event_ids = original_outbox_event_ids
            local_e2e_runner.seed_product = original_seed_product
            local_e2e_runner.seed_stock = original_seed_stock
            local_e2e_runner.run_docker_compose_action = original_run_docker_compose_action
            local_e2e_runner.create_order = original_create_order

        self.assertEqual(actions, ["pause", "unpause"])

    def test_retryable_pending_outbox_items_excludes_future_retry_rows(self) -> None:
        now = local_e2e_runner.datetime(2031, 1, 2, 3, 4, 5, tzinfo=local_e2e_runner.timezone.utc)
        items = [
            {"eventId": "due-null", "status": "PENDING", "nextRetryAt": None},
            {"eventId": "due-past", "status": "PENDING", "nextRetryAt": "2031-01-02T03:04:04Z"},
            {"eventId": "future", "status": "PENDING", "nextRetryAt": "2031-01-02T03:05:05Z"},
            {"eventId": "failed", "status": "FAILED", "nextRetryAt": None},
        ]

        retryable = local_e2e_runner.retryable_pending_outbox_items(items, now=now)

        self.assertEqual([item["eventId"] for item in retryable], ["due-null", "due-past"])

    def test_outbox_recovery_snapshot_counts_failed_retryable_and_deferred_rows(self) -> None:
        now = local_e2e_runner.datetime(2031, 1, 2, 3, 4, 5, tzinfo=local_e2e_runner.timezone.utc)
        snapshot = local_e2e_runner.outbox_recovery_snapshot_from_items(
            {
                "order": [
                    {"eventId": "ord-1", "status": "PENDING", "nextRetryAt": None},
                    {"eventId": "ord-2", "status": "PENDING", "nextRetryAt": "2031-01-02T03:05:05Z"},
                    {"eventId": "ord-3", "status": "FAILED", "nextRetryAt": None},
                ],
                "inventory": [],
                "payment": [
                    {"eventId": "pay-1", "status": "FAILED", "nextRetryAt": None},
                ],
            },
            now=now,
        )

        self.assertEqual(snapshot["pendingCounts"], {"order": 2, "inventory": 0, "payment": 0})
        self.assertEqual(snapshot["retryablePendingCounts"], {"order": 1, "inventory": 0, "payment": 0})
        self.assertEqual(snapshot["deferredPendingCounts"], {"order": 1, "inventory": 0, "payment": 0})
        self.assertEqual(snapshot["failedCounts"], {"order": 1, "inventory": 0, "payment": 1})

    def test_validate_outbox_recovery_result_accepts_no_failed_or_retryable_pending(self) -> None:
        errors = local_e2e_runner.validate_outbox_recovery_result(
            {
                "retryablePendingCounts": {"order": 3, "inventory": 0, "payment": 0},
                "failedCounts": {"order": 1, "inventory": 0, "payment": 0},
            },
            {
                "retryablePendingCounts": {"order": 0, "inventory": 0, "payment": 0},
                "failedCounts": {"order": 0, "inventory": 0, "payment": 0},
            },
        )

        self.assertEqual(errors, [])

    def test_validate_outbox_recovery_result_reports_unrecovered_rows(self) -> None:
        errors = local_e2e_runner.validate_outbox_recovery_result(
            {
                "retryablePendingCounts": {"order": 0, "inventory": 0, "payment": 0},
                "failedCounts": {"order": 0, "inventory": 0, "payment": 0},
            },
            {
                "retryablePendingCounts": {"order": 1, "inventory": 0, "payment": 0},
                "failedCounts": {"order": 0, "inventory": 2, "payment": 0},
            },
        )

        self.assertTrue(any("retryable pending" in error for error in errors))
        self.assertTrue(any("failed outbox" in error for error in errors))

    def test_outbox_recovery_healthcheck_only_checks_required_services(self) -> None:
        args = local_e2e_runner.parse_args([
            "outbox-recovery",
            "--catalog-url",
            "http://catalog.local",
            "--promotion-url",
            "http://promotion.local",
            "--outbox-api-url",
            "http://gateway.local",
            "--order-url",
            "http://order.local",
            "--inventory-url",
            "http://inventory.local",
            "--payment-url",
            "http://payment.local",
        ])
        config = local_e2e_runner.config_from_args(args)

        class FakeClient:
            def __init__(self) -> None:
                self.urls: list[str] = []

            def get(self, url: str) -> dict[str, str]:
                self.urls.append(url)
                return {"status": "UP"}

        client = FakeClient()

        local_e2e_runner.outbox_recovery_healthcheck(client, config)

        self.assertEqual(
            client.urls,
            [
                "http://gateway.local/actuator/health",
                "http://order.local/actuator/health",
                "http://inventory.local/actuator/health",
                "http://payment.local/actuator/health",
            ],
        )

    def test_main_reports_runtime_error_as_json_without_traceback(self) -> None:
        original = local_e2e_runner.run_outbox_recovery_scenario

        def fail_recovery(_config: object) -> None:
            raise RuntimeError("service unavailable")

        local_e2e_runner.run_outbox_recovery_scenario = fail_recovery
        try:
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                exit_code = local_e2e_runner.main(["outbox-recovery"])
        finally:
            local_e2e_runner.run_outbox_recovery_scenario = original

        payload = json.loads(stderr.getvalue())
        self.assertEqual(exit_code, 1)
        self.assertEqual(payload["errors"], ["service unavailable"])
        self.assertNotIn("Traceback", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
