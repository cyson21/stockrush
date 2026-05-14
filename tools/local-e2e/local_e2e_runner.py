#!/usr/bin/env python3
"""Run local StockRush E2E scenarios against already running services."""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Mapping, Sequence
from uuid import uuid4


DEFAULT_CATALOG_URL = "http://localhost:18081"
DEFAULT_INVENTORY_URL = "http://localhost:18082"
DEFAULT_ORDER_URL = "http://localhost:18083"
DEFAULT_ORDER_API_URL = "http://localhost:18080"
DEFAULT_OUTBOX_API_URL = "http://localhost:18080"
DEFAULT_PAYMENT_URL = "http://localhost:18084"
SERVICE_BASES = ("order", "inventory", "payment")
MAX_GENERATED_PREFIX_LENGTH = 48
MAX_RELAY_BATCH_SIZE = 100
OUTBOX_QUERY_LIMIT = 200


@dataclass(frozen=True)
class OrderSummary:
    confirmed: int
    cancelled: int
    unresolved_order_ids: list[str]


@dataclass(frozen=True)
class ScenarioConfig:
    catalog_url: str
    inventory_url: str
    order_url: str
    order_api_url: str
    outbox_api_url: str
    payment_url: str
    order_count: int
    initial_stock: int
    quantity_per_order: int
    unit_price: int
    prefix: str
    max_attempts: int
    relay_batch_size: int
    wait_seconds: float
    fail_on_existing_pending: bool


def expected_success_count(initial_stock: int, quantity_per_order: int, order_count: int) -> int:
    if quantity_per_order < 1:
        raise ValueError("quantity_per_order must be at least 1")
    return min(order_count, initial_stock // quantity_per_order)


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be at least 1")
    return parsed


def non_negative_int(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be at least 0")
    return parsed


def non_negative_float(value: str) -> float:
    parsed = float(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be at least 0")
    return parsed


def relay_batch_size(value: str) -> int:
    parsed = positive_int(value)
    if parsed > MAX_RELAY_BATCH_SIZE:
        raise argparse.ArgumentTypeError(f"must be {MAX_RELAY_BATCH_SIZE} or fewer")
    return parsed


def scenario_prefix(value: str) -> str:
    normalized = value.strip()
    if not normalized:
        raise argparse.ArgumentTypeError("must not be blank")
    if len(normalized) > MAX_GENERATED_PREFIX_LENGTH:
        raise argparse.ArgumentTypeError(f"must be {MAX_GENERATED_PREFIX_LENGTH} characters or fewer")
    return normalized


def scenario_ids(prefix: str) -> tuple[str, str]:
    normalized = scenario_prefix(prefix)
    suffix = f"{datetime.now().strftime('%Y%m%d%H%M%S')}-{uuid4().hex[:8]}"
    product_code = f"{normalized}-{suffix}"
    return product_code, f"{product_code}-S"


def summarize_orders(orders: Sequence[Mapping[str, Any]]) -> OrderSummary:
    confirmed = 0
    cancelled = 0
    unresolved: list[str] = []
    for order in orders:
        status = order.get("status")
        saga_status = order.get("sagaStatus")
        if status == "CONFIRMED" and saga_status == "COMPLETED":
            confirmed += 1
        elif status == "CANCELLED" and saga_status == "FAILED":
            cancelled += 1
        else:
            unresolved.append(str(order.get("orderId", "<missing>")))
    return OrderSummary(confirmed=confirmed, cancelled=cancelled, unresolved_order_ids=unresolved)


def validate_concurrent_sku_result(
    *,
    orders: Sequence[Mapping[str, Any]],
    stock: Mapping[str, Any],
    initial_stock: int,
    quantity_per_order: int,
    order_count: int,
    pending_outbox_counts: Mapping[str, int],
) -> list[str]:
    errors: list[str] = []
    summary = summarize_orders(orders)
    expected_confirmed = expected_success_count(initial_stock, quantity_per_order, order_count)
    expected_cancelled = order_count - expected_confirmed
    expected_available = initial_stock - (expected_confirmed * quantity_per_order)

    if summary.confirmed != expected_confirmed:
        errors.append(f"confirmed count expected {expected_confirmed}, got {summary.confirmed}")
    if summary.cancelled != expected_cancelled:
        errors.append(f"cancelled count expected {expected_cancelled}, got {summary.cancelled}")
    if summary.unresolved_order_ids:
        errors.append(f"unresolved orders: {', '.join(summary.unresolved_order_ids)}")
    if stock.get("availableQuantity") != expected_available or stock.get("reservedQuantity") != 0:
        errors.append(
            "stock expected "
            f"availableQuantity={expected_available}, reservedQuantity=0; "
            f"got availableQuantity={stock.get('availableQuantity')}, "
            f"reservedQuantity={stock.get('reservedQuantity')}"
        )

    pending = {name: count for name, count in pending_outbox_counts.items() if count}
    if pending:
        errors.append(f"pending outbox remains: {pending}")
    return errors


def validate_demo_order_flow_result(
    *,
    card_order: Mapping[str, Any],
    fail_order: Mapping[str, Any],
    delay_order: Mapping[str, Any],
    stock: Mapping[str, Any],
    initial_stock: int,
    quantity_per_order: int,
    pending_outbox_counts: Mapping[str, int],
) -> list[str]:
    errors: list[str] = []
    expected_available = initial_stock - quantity_per_order

    if not is_order_state(card_order, "CONFIRMED", "COMPLETED"):
        errors.append(
            "CARD order expected CONFIRMED/COMPLETED; "
            f"got {card_order.get('status')}/{card_order.get('sagaStatus')}"
        )
    if not is_order_state(fail_order, "CANCELLED", "FAILED"):
        errors.append(
            "FAIL_CARD order expected CANCELLED/FAILED; "
            f"got {fail_order.get('status')}/{fail_order.get('sagaStatus')}"
        )
    if not is_order_state(delay_order, "CANCELLED", "FAILED"):
        errors.append(
            "DELAY_CARD order expected CANCELLED/FAILED after admin cancel; "
            f"got {delay_order.get('status')}/{delay_order.get('sagaStatus')}"
        )
    if stock.get("availableQuantity") != expected_available or stock.get("reservedQuantity") != 0:
        errors.append(
            "stock expected "
            f"availableQuantity={expected_available}, reservedQuantity=0; "
            f"got availableQuantity={stock.get('availableQuantity')}, "
            f"reservedQuantity={stock.get('reservedQuantity')}"
        )

    pending = {name: count for name, count in pending_outbox_counts.items() if count}
    if pending:
        errors.append(f"pending outbox remains: {pending}")
    return errors


def is_order_state(order: Mapping[str, Any], status: str, saga_status: str) -> bool:
    return order.get("status") == status and order.get("sagaStatus") == saga_status


class ApiClient:
    def __init__(self, timeout_seconds: float = 30.0) -> None:
        self.timeout_seconds = timeout_seconds

    def get(self, url: str) -> Mapping[str, Any]:
        return self.request("GET", url)

    def post(self, url: str, body: Mapping[str, Any] | None = None, headers: Mapping[str, str] | None = None) -> Mapping[str, Any]:
        return self.request("POST", url, body=body, headers=headers)

    def put(self, url: str, body: Mapping[str, Any], headers: Mapping[str, str] | None = None) -> Mapping[str, Any]:
        return self.request("PUT", url, body=body, headers=headers)

    def request(
        self,
        method: str,
        url: str,
        body: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> Mapping[str, Any]:
        request_headers = dict(headers or {})
        payload = None
        if body is not None:
            payload = json.dumps(body).encode("utf-8")
            request_headers.setdefault("Content-Type", "application/json")
        request = urllib.request.Request(url, data=payload, method=method, headers=request_headers)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8")
            raise RuntimeError(f"HTTP {error.code} {method} {url}: {raw}") from error
        return json.loads(raw) if raw else {}


def response_data(payload: Mapping[str, Any]) -> Any:
    return payload.get("data", payload)


def count_pending_outbox(client: ApiClient, config: ScenarioConfig) -> dict[str, int]:
    counts: dict[str, int] = {}
    for name in SERVICE_BASES:
        payload = response_data(client.get(outbox_list_url(config, name)))
        counts[name] = len(payload.get("items", []))
    return counts


def outbox_list_url(config: ScenarioConfig, service: str) -> str:
    return (
        f"{config.outbox_api_url}/api/admin/outbox-services/{service}/events"
        f"?status=PENDING&limit={OUTBOX_QUERY_LIMIT}"
    )


def outbox_retry_url(config: ScenarioConfig, service: str) -> str:
    return (
        f"{config.outbox_api_url}/api/admin/outbox-services/{service}/events/retry"
        f"?batchSize={config.relay_batch_size}"
    )


def pending_outbox_delta(before: Mapping[str, int], after: Mapping[str, int]) -> dict[str, int]:
    return {name: max(0, after.get(name, 0) - before.get(name, 0)) for name in SERVICE_BASES}


def ensure_no_pending_outbox(client: ApiClient, config: ScenarioConfig) -> None:
    if not config.fail_on_existing_pending:
        return
    pending = count_pending_outbox(client, config)
    remains = {name: count for name, count in pending.items() if count}
    if remains:
        raise RuntimeError(f"existing pending outbox rows must be drained first: {remains}")


def healthcheck(client: ApiClient, config: ScenarioConfig) -> None:
    for base_url in dict.fromkeys([
        config.catalog_url,
        config.inventory_url,
        config.order_url,
        config.order_api_url,
        config.outbox_api_url,
        config.payment_url,
    ]):
        payload = client.get(f"{base_url}/actuator/health")
        if payload.get("status") != "UP":
            raise RuntimeError(f"service is not healthy: {base_url} -> {payload}")


def run_concurrent_sku_scenario(config: ScenarioConfig) -> Mapping[str, Any]:
    client = ApiClient(timeout_seconds=90.0)
    product_code, sku_id = scenario_ids(config.prefix)

    healthcheck(client, config)
    ensure_no_pending_outbox(client, config)
    pending_before = count_pending_outbox(client, config)
    seed_product(client, config, product_code)
    seed_stock(client, config, product_code, sku_id)
    orders = create_orders_concurrently(client, config, product_code, sku_id)

    final_orders: list[Mapping[str, Any]] = []
    final_stock: Mapping[str, Any] = {}
    pending_after = pending_before
    for attempt in range(1, config.max_attempts + 1):
        relay_wave(client, config)
        final_orders = [get_order(client, config, order["orderId"]) for order in orders]
        final_stock = get_stock(client, config, sku_id)
        pending_after = count_pending_outbox(client, config)
        pending_delta = pending_outbox_delta(pending_before, pending_after)
        if not summarize_orders(final_orders).unresolved_order_ids and not any(pending_delta.values()):
            break
        time.sleep(config.wait_seconds)
    else:
        pending_after = count_pending_outbox(client, config)
        pending_delta = pending_outbox_delta(pending_before, pending_after)

    errors = validate_concurrent_sku_result(
        orders=final_orders,
        stock=final_stock,
        initial_stock=config.initial_stock,
        quantity_per_order=config.quantity_per_order,
        order_count=config.order_count,
        pending_outbox_counts=pending_delta,
    )

    return {
        "productCode": product_code,
        "skuId": sku_id,
        "orderIds": [order["orderId"] for order in orders],
        "orders": final_orders,
        "stock": final_stock,
        "pendingOutboxBaseline": pending_before,
        "pendingOutboxCounts": pending_after,
        "pendingOutboxDelta": pending_delta,
        "summary": summarize_orders(final_orders).__dict__,
        "errors": errors,
    }


def run_demo_order_flow_scenario(config: ScenarioConfig) -> Mapping[str, Any]:
    client = ApiClient(timeout_seconds=90.0)
    product_code, sku_id = scenario_ids(config.prefix)

    healthcheck(client, config)
    ensure_no_pending_outbox(client, config)
    pending_before = count_pending_outbox(client, config)
    seed_product(client, config, product_code)
    seed_stock(client, config, product_code, sku_id)

    card_order = create_order(client, config, product_code, sku_id, "CARD", "member-demo-card", 1)
    fail_order = create_order(client, config, product_code, sku_id, "FAIL_CARD", "member-demo-fail", 2)
    delay_order = create_order(client, config, product_code, sku_id, "DELAY_CARD", "member-demo-delay", 3)

    orders = {
        "card": card_order,
        "fail": fail_order,
        "delay": delay_order,
    }
    pending_after = pending_before
    for _ in range(config.max_attempts):
        relay_wave(client, config)
        orders = {
            "card": get_order(client, config, str(card_order["orderId"])),
            "fail": get_order(client, config, str(fail_order["orderId"])),
            "delay": get_order(client, config, str(delay_order["orderId"])),
        }
        pending_after = count_pending_outbox(client, config)
        if (
            is_order_state(orders["card"], "CONFIRMED", "COMPLETED")
            and is_order_state(orders["fail"], "CANCELLED", "FAILED")
            and is_order_state(orders["delay"], "CREATED", "PAYMENT_DELAYED")
        ):
            break
        time.sleep(config.wait_seconds)

    if is_order_state(orders["delay"], "CREATED", "PAYMENT_DELAYED"):
        cancel_delayed_order(client, config, str(delay_order["orderId"]), product_code)

    final_stock: Mapping[str, Any] = {}
    for _ in range(config.max_attempts):
        relay_wave(client, config)
        orders = {
            "card": get_order(client, config, str(card_order["orderId"])),
            "fail": get_order(client, config, str(fail_order["orderId"])),
            "delay": get_order(client, config, str(delay_order["orderId"])),
        }
        final_stock = get_stock(client, config, sku_id)
        pending_after = count_pending_outbox(client, config)
        pending_delta = pending_outbox_delta(pending_before, pending_after)
        if (
            is_order_state(orders["card"], "CONFIRMED", "COMPLETED")
            and is_order_state(orders["fail"], "CANCELLED", "FAILED")
            and is_order_state(orders["delay"], "CANCELLED", "FAILED")
            and final_stock.get("reservedQuantity") == 0
            and not any(pending_delta.values())
        ):
            break
        time.sleep(config.wait_seconds)
    else:
        final_stock = get_stock(client, config, sku_id)
        pending_after = count_pending_outbox(client, config)
        pending_delta = pending_outbox_delta(pending_before, pending_after)

    errors = validate_demo_order_flow_result(
        card_order=orders["card"],
        fail_order=orders["fail"],
        delay_order=orders["delay"],
        stock=final_stock,
        initial_stock=config.initial_stock,
        quantity_per_order=config.quantity_per_order,
        pending_outbox_counts=pending_delta,
    )

    return {
        "productCode": product_code,
        "skuId": sku_id,
        "orders": orders,
        "stock": final_stock,
        "pendingOutboxBaseline": pending_before,
        "pendingOutboxCounts": pending_after,
        "pendingOutboxDelta": pending_delta,
        "errors": errors,
    }


def seed_product(client: ApiClient, config: ScenarioConfig, product_code: str) -> None:
    client.post(
        f"{config.catalog_url}/api/admin/products",
        {
            "productCode": product_code,
            "name": "Concurrent E2E Product",
            "salesStatus": "ON_SALE",
            "listPrice": config.unit_price,
        },
        headers={"Idempotency-Key": f"idem-product-{product_code}"},
    )


def seed_stock(client: ApiClient, config: ScenarioConfig, product_code: str, sku_id: str) -> None:
    client.put(
        f"{config.inventory_url}/api/stocks/{sku_id}",
        {"productCode": product_code, "availableQuantity": config.initial_stock},
        headers={"Idempotency-Key": f"idem-stock-{sku_id}"},
    )


def create_order(
    client: ApiClient,
    config: ScenarioConfig,
    product_code: str,
    sku_id: str,
    payment_method: str,
    member_id: str,
    index: int,
) -> Mapping[str, Any]:
    return response_data(
        client.post(
            f"{config.order_api_url}/api/orders",
            {
                "memberId": member_id,
                "paymentMethod": payment_method,
                "items": [
                    {
                        "productCode": product_code,
                        "skuId": sku_id,
                        "quantity": config.quantity_per_order,
                        "unitPrice": config.unit_price,
                    }
                ],
            },
            headers={"Idempotency-Key": f"idem-order-{product_code}-{index:02d}-{payment_method}"},
        )
    )


def cancel_delayed_order(client: ApiClient, config: ScenarioConfig, order_id: str, product_code: str) -> None:
    client.post(
        f"{config.order_api_url}/api/admin/orders/{order_id}/cancel",
        headers={"Idempotency-Key": f"idem-admin-cancel-{product_code}-{order_id}"},
    )


def create_orders_concurrently(
    client: ApiClient,
    config: ScenarioConfig,
    product_code: str,
    sku_id: str,
) -> list[Mapping[str, Any]]:
    def create_concurrent_order(index: int) -> Mapping[str, Any]:
        payload = response_data(
            client.post(
                f"{config.order_api_url}/api/orders",
                {
                    "memberId": f"member-concurrent-{index:02d}",
                    "paymentMethod": "CARD",
                    "items": [
                        {
                            "productCode": product_code,
                            "skuId": sku_id,
                            "quantity": config.quantity_per_order,
                            "unitPrice": config.unit_price,
                        }
                    ],
                },
                headers={"Idempotency-Key": f"idem-order-{product_code}-{index:02d}"},
            )
        )
        return payload

    results: list[Mapping[str, Any]] = []
    with ThreadPoolExecutor(max_workers=config.order_count) as executor:
        futures = [executor.submit(create_concurrent_order, index) for index in range(1, config.order_count + 1)]
        for future in as_completed(futures):
            results.append(future.result())
    return sorted(results, key=lambda order: str(order["orderId"]))


def relay_wave(client: ApiClient, config: ScenarioConfig) -> None:
    relay_order = ["order", "inventory", "order", "payment", "order", "inventory"]
    for service in relay_order:
        client.post(outbox_retry_url(config, service))
        time.sleep(config.wait_seconds)


def get_order(client: ApiClient, config: ScenarioConfig, order_id: str) -> Mapping[str, Any]:
    return response_data(client.get(f"{config.order_api_url}/api/orders/{order_id}"))


def get_stock(client: ApiClient, config: ScenarioConfig, sku_id: str) -> Mapping[str, Any]:
    return response_data(client.get(f"{config.inventory_url}/api/stocks/{sku_id}"))


def add_runtime_arguments(
    command_parser: argparse.ArgumentParser,
    *,
    default_orders: int,
    default_initial_stock: int,
    default_prefix: str,
) -> None:
    command_parser.add_argument("--catalog-url", default=DEFAULT_CATALOG_URL)
    command_parser.add_argument("--inventory-url", default=DEFAULT_INVENTORY_URL)
    command_parser.add_argument("--order-url", default=DEFAULT_ORDER_URL, help="Order Service health URL")
    command_parser.add_argument(
        "--order-api-url",
        default=DEFAULT_ORDER_API_URL,
        help="Public order create/query URL. Defaults to Gateway at http://localhost:18080.",
    )
    command_parser.add_argument(
        "--outbox-api-url",
        default=DEFAULT_OUTBOX_API_URL,
        help="Outbox admin routing URL. Defaults to Gateway at http://localhost:18080.",
    )
    command_parser.add_argument("--payment-url", default=DEFAULT_PAYMENT_URL)
    command_parser.add_argument("--orders", type=positive_int, default=default_orders)
    command_parser.add_argument("--initial-stock", type=non_negative_int, default=default_initial_stock)
    command_parser.add_argument("--quantity", type=positive_int, default=1)
    command_parser.add_argument("--unit-price", type=positive_int, default=12000)
    command_parser.add_argument("--prefix", type=scenario_prefix, default=default_prefix)
    command_parser.add_argument("--max-attempts", type=positive_int, default=12)
    command_parser.add_argument("--relay-batch-size", type=relay_batch_size, default=100)
    command_parser.add_argument("--wait-seconds", type=non_negative_float, default=0.5)
    command_parser.add_argument(
        "--allow-existing-pending",
        action="store_true",
        help="Skip the preflight failure when existing PENDING outbox rows are present.",
    )


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="StockRush local E2E runner")
    subparsers = parser.add_subparsers(dest="command", required=True)

    concurrent = subparsers.add_parser(
        "same-sku-concurrency",
        aliases=["concurrent-sku"],
        help="Run same-SKU concurrent order final-state E2E",
    )
    add_runtime_arguments(
        concurrent,
        default_orders=6,
        default_initial_stock=3,
        default_prefix="CONC-E2E",
    )

    demo_order_flow = subparsers.add_parser(
        "demo-order-flow",
        aliases=["cards-smoke"],
        help="Run CARD, FAIL_CARD, and DELAY_CARD demo order flow E2E",
    )
    add_runtime_arguments(
        demo_order_flow,
        default_orders=3,
        default_initial_stock=20,
        default_prefix="DEMO-E2E",
    )
    return parser.parse_args(argv)


def config_from_args(args: argparse.Namespace) -> ScenarioConfig:
    order_url = args.order_url.rstrip("/")
    return ScenarioConfig(
        catalog_url=args.catalog_url.rstrip("/"),
        inventory_url=args.inventory_url.rstrip("/"),
        order_url=order_url,
        order_api_url=args.order_api_url.rstrip("/"),
        outbox_api_url=args.outbox_api_url.rstrip("/"),
        payment_url=args.payment_url.rstrip("/"),
        order_count=args.orders,
        initial_stock=args.initial_stock,
        quantity_per_order=args.quantity,
        unit_price=args.unit_price,
        prefix=args.prefix,
        max_attempts=args.max_attempts,
        relay_batch_size=args.relay_batch_size,
        wait_seconds=args.wait_seconds,
        fail_on_existing_pending=not args.allow_existing_pending,
    )


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    if args.command in {"same-sku-concurrency", "concurrent-sku"}:
        result = run_concurrent_sku_scenario(config_from_args(args))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1 if result["errors"] else 0
    if args.command in {"demo-order-flow", "cards-smoke"}:
        result = run_demo_order_flow_scenario(config_from_args(args))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1 if result["errors"] else 0
    raise AssertionError(f"Unsupported command: {args.command}")


if __name__ == "__main__":
    raise SystemExit(main())
