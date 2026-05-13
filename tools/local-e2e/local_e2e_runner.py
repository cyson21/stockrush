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
    urls = {
        "order": config.order_url,
        "inventory": config.inventory_url,
        "payment": config.payment_url,
    }
    counts: dict[str, int] = {}
    for name, base_url in urls.items():
        payload = response_data(client.get(f"{base_url}/api/admin/outbox-events?status=PENDING&limit={OUTBOX_QUERY_LIMIT}"))
        counts[name] = len(payload.get("items", []))
    return counts


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
    for base_url in [config.catalog_url, config.inventory_url, config.order_url, config.payment_url]:
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


def create_orders_concurrently(
    client: ApiClient,
    config: ScenarioConfig,
    product_code: str,
    sku_id: str,
) -> list[Mapping[str, Any]]:
    def create_order(index: int) -> Mapping[str, Any]:
        payload = response_data(
            client.post(
                f"{config.order_url}/api/orders",
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
        futures = [executor.submit(create_order, index) for index in range(1, config.order_count + 1)]
        for future in as_completed(futures):
            results.append(future.result())
    return sorted(results, key=lambda order: str(order["orderId"]))


def relay_wave(client: ApiClient, config: ScenarioConfig) -> None:
    relay_order = [config.order_url, config.inventory_url, config.order_url, config.payment_url, config.order_url, config.inventory_url]
    for base_url in relay_order:
        client.post(f"{base_url}/api/admin/outbox-events/retry?batchSize={config.relay_batch_size}")
        time.sleep(config.wait_seconds)


def get_order(client: ApiClient, config: ScenarioConfig, order_id: str) -> Mapping[str, Any]:
    return response_data(client.get(f"{config.order_url}/api/orders/{order_id}"))


def get_stock(client: ApiClient, config: ScenarioConfig, sku_id: str) -> Mapping[str, Any]:
    return response_data(client.get(f"{config.inventory_url}/api/stocks/{sku_id}"))


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="StockRush local E2E runner")
    subparsers = parser.add_subparsers(dest="command", required=True)

    concurrent = subparsers.add_parser(
        "same-sku-concurrency",
        aliases=["concurrent-sku"],
        help="Run same-SKU concurrent order final-state E2E",
    )
    concurrent.add_argument("--catalog-url", default=DEFAULT_CATALOG_URL)
    concurrent.add_argument("--inventory-url", default=DEFAULT_INVENTORY_URL)
    concurrent.add_argument("--order-url", default=DEFAULT_ORDER_URL)
    concurrent.add_argument("--payment-url", default=DEFAULT_PAYMENT_URL)
    concurrent.add_argument("--orders", type=positive_int, default=6)
    concurrent.add_argument("--initial-stock", type=non_negative_int, default=3)
    concurrent.add_argument("--quantity", type=positive_int, default=1)
    concurrent.add_argument("--unit-price", type=positive_int, default=12000)
    concurrent.add_argument("--prefix", type=scenario_prefix, default="CONC-E2E")
    concurrent.add_argument("--max-attempts", type=positive_int, default=12)
    concurrent.add_argument("--relay-batch-size", type=relay_batch_size, default=100)
    concurrent.add_argument("--wait-seconds", type=non_negative_float, default=0.5)
    concurrent.add_argument(
        "--allow-existing-pending",
        action="store_true",
        help="Skip the preflight failure when existing PENDING outbox rows are present.",
    )
    return parser.parse_args(argv)


def config_from_args(args: argparse.Namespace) -> ScenarioConfig:
    return ScenarioConfig(
        catalog_url=args.catalog_url.rstrip("/"),
        inventory_url=args.inventory_url.rstrip("/"),
        order_url=args.order_url.rstrip("/"),
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
    raise AssertionError(f"Unsupported command: {args.command}")


if __name__ == "__main__":
    raise SystemExit(main())
