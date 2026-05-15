#!/usr/bin/env python3
"""Run local StockRush E2E scenarios against already running services."""
from __future__ import annotations

import argparse
import os
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Mapping, Sequence
from uuid import uuid4


DEFAULT_CATALOG_URL = "http://localhost:18081"
DEFAULT_INVENTORY_URL = "http://localhost:18082"
DEFAULT_ORDER_URL = "http://localhost:18083"
DEFAULT_ORDER_API_URL = "http://localhost:18080"
DEFAULT_OUTBOX_API_URL = "http://localhost:18080"
DEFAULT_PAYMENT_URL = "http://localhost:18084"
DEFAULT_PROMOTION_URL = "http://localhost:18085"
SERVICE_BASES = ("order", "inventory", "payment")
MAX_GENERATED_PREFIX_LENGTH = 48
MAX_RELAY_BATCH_SIZE = 100
MAX_BURST_WORKERS = 64
OUTBOX_QUERY_LIMIT = 200
DEMO_COUPON_DISCOUNT_AMOUNT = 1000
KAFKA_OUTAGE_OBSERVATION_STATUSES = ("PENDING", "PUBLISHING", "FAILED")


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
    promotion_url: str
    order_count: int
    initial_stock: int
    quantity_per_order: int
    unit_price: int
    prefix: str
    max_attempts: int
    relay_batch_size: int
    wait_seconds: float
    fail_on_existing_pending: bool
    relay_mode: str = "manual"
    idempotency_replays: int = 1
    relay_workers: int = 1
    stability_waves: int = 1
    operator_id: str = "local-e2e"
    requeue_failed: bool = True
    compose_file: str | None = None
    env_file: str | None = None
    kafka_service: str = "kafka"
    outage_observation_seconds: float = 2.0
    admin_bearer_token: str | None = None


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


def non_blank_text(value: str) -> str:
    normalized = value.strip()
    if not normalized:
        raise argparse.ArgumentTypeError("must not be blank")
    return normalized


def normalize_admin_bearer_token(value: str | None) -> str | None:
    if not value:
        return None
    token = value.strip()
    if not token:
        return None
    if token.startswith("Bearer "):
        token = token[len("Bearer "):].strip()
    return token or None


def scenario_ids(prefix: str) -> tuple[str, str]:
    normalized = scenario_prefix(prefix)
    suffix = f"{datetime.now().strftime('%Y%m%d%H%M%S')}-{uuid4().hex[:8]}"
    product_code = f"{normalized}-{suffix}"
    return product_code, f"{product_code}-S"


def coupon_code_for_product(product_code: str) -> str:
    return f"{product_code}-C"


def demo_order_amount(config: ScenarioConfig) -> int:
    return config.unit_price * config.quantity_per_order


def demo_coupon_discount_amount(config: ScenarioConfig) -> int:
    return min(DEMO_COUPON_DISCOUNT_AMOUNT, demo_order_amount(config))


def demo_coupon_payable_amount(config: ScenarioConfig) -> int:
    return max(0, demo_order_amount(config) - demo_coupon_discount_amount(config))


def demo_coupon_period(now: datetime | None = None) -> tuple[str, str]:
    base = now or datetime.now(timezone.utc)
    starts_at = base - timedelta(days=1)
    ends_at = base + timedelta(days=365)
    return format_utc_instant(starts_at), format_utc_instant(ends_at)


def format_utc_instant(value: datetime) -> str:
    return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


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

    pending = positive_pending_outbox_changes(pending_outbox_counts)
    if pending:
        errors.append(f"pending outbox remains: {pending}")
    return errors


def order_ids_by_request_index(attempts: Sequence[Mapping[str, Any]]) -> dict[int, list[str]]:
    grouped: dict[int, list[str]] = {}
    for attempt in attempts:
        index = int(attempt["index"])
        grouped.setdefault(index, []).append(str(attempt.get("orderId", "<missing>")))
    return {index: grouped[index] for index in sorted(grouped)}


def unique_orders_from_attempts(attempts: Sequence[Mapping[str, Any]]) -> list[Mapping[str, Any]]:
    orders_by_index: dict[int, Mapping[str, Any]] = {}
    for attempt in attempts:
        index = int(attempt["index"])
        if index not in orders_by_index:
            orders_by_index[index] = attempt["order"]  # type: ignore[assignment]
    return [orders_by_index[index] for index in sorted(orders_by_index)]


def validate_burst_idempotency_result(
    *,
    orders: Sequence[Mapping[str, Any]],
    stock: Mapping[str, Any],
    initial_stock: int,
    quantity_per_order: int,
    order_count: int,
    pending_outbox_counts: Mapping[str, int],
    new_pending_outbox_event_ids: Mapping[str, Sequence[str]] | None = None,
    replay_order_ids_by_index: Mapping[int, Sequence[str]],
    request_attempt_count: int,
    idempotency_replays: int,
    post_replay_orders: Sequence[Mapping[str, Any]],
    post_replay_stock: Mapping[str, Any],
    post_replay_pending_outbox_counts: Mapping[str, int],
    post_replay_new_pending_outbox_event_ids: Mapping[str, Sequence[str]] | None = None,
) -> list[str]:
    errors = validate_concurrent_sku_result(
        orders=orders,
        stock=stock,
        initial_stock=initial_stock,
        quantity_per_order=quantity_per_order,
        order_count=order_count,
        pending_outbox_counts=pending_outbox_counts,
    )
    new_pending = {name: list(ids) for name, ids in (new_pending_outbox_event_ids or {}).items() if ids}
    if new_pending:
        errors.append(f"new pending outbox events remain: {new_pending}")

    if len(orders) != order_count:
        errors.append(f"unique order count expected {order_count}, got {len(orders)}")
    expected_attempt_count = order_count * idempotency_replays
    if request_attempt_count != expected_attempt_count:
        errors.append(f"request attempt count expected {expected_attempt_count}, got {request_attempt_count}")

    order_ids = [str(order.get("orderId", "<missing>")) for order in orders]
    duplicated_order_ids = sorted({order_id for order_id in order_ids if order_ids.count(order_id) > 1})
    if duplicated_order_ids:
        errors.append(f"duplicate order ids in final orders: {duplicated_order_ids}")

    for index in range(1, order_count + 1):
        replay_order_ids = [str(order_id) for order_id in replay_order_ids_by_index.get(index, [])]
        unique_replay_order_ids = sorted(set(replay_order_ids))
        if not replay_order_ids:
            errors.append(f"idempotency replay index {index} has no order response")
        elif len(replay_order_ids) != idempotency_replays:
            errors.append(
                "idempotency replay index "
                f"{index} expected {idempotency_replays} attempts, got {len(replay_order_ids)}"
            )
        elif len(unique_replay_order_ids) != 1:
            errors.append(
                f"idempotency replay index {index} returned multiple orderIds: {unique_replay_order_ids}"
            )

    summary = summarize_orders(orders)
    post_replay_summary = summarize_orders(post_replay_orders)
    if post_replay_summary != summary:
        errors.append(
            "post-replay summary drift expected "
            f"{summary.__dict__}, got {post_replay_summary.__dict__}"
        )

    if order_state_snapshot(post_replay_orders) != order_state_snapshot(orders):
        errors.append(
            "post-replay order state drift expected "
            f"{order_state_snapshot(orders)}, got {order_state_snapshot(post_replay_orders)}"
        )

    if stock_quantity_snapshot(post_replay_stock) != stock_quantity_snapshot(stock):
        errors.append(
            "post-replay stock drift expected "
            f"{stock_quantity_snapshot(stock)}, got {stock_quantity_snapshot(post_replay_stock)}"
        )

    pending = positive_pending_outbox_changes(post_replay_pending_outbox_counts)
    if pending:
        errors.append(f"post-replay pending outbox remains: {pending}")
    post_replay_new_pending = {
        name: list(ids)
        for name, ids in (post_replay_new_pending_outbox_event_ids or {}).items()
        if ids
    }
    if post_replay_new_pending:
        errors.append(f"post-replay new pending outbox events remain: {post_replay_new_pending}")
    return errors


def positive_pending_outbox_changes(changes: Mapping[str, int]) -> dict[str, int]:
    return {name: count for name, count in changes.items() if count > 0}


def new_pending_outbox_event_ids(
    before: Mapping[str, set[str]],
    after: Mapping[str, set[str]],
) -> dict[str, list[str]]:
    return {
        name: sorted(after.get(name, set()) - before.get(name, set()))
        for name in SERVICE_BASES
        if after.get(name, set()) - before.get(name, set())
    }


def order_state_snapshot(orders: Sequence[Mapping[str, Any]]) -> list[tuple[str, Any, Any]]:
    return sorted(
        (str(order.get("orderId", "<missing>")), order.get("status"), order.get("sagaStatus"))
        for order in orders
    )


def stock_quantity_snapshot(stock: Mapping[str, Any]) -> tuple[Any, Any]:
    return stock.get("availableQuantity"), stock.get("reservedQuantity")


def validate_demo_order_flow_result(
    *,
    card_order: Mapping[str, Any],
    fail_order: Mapping[str, Any],
    delay_order: Mapping[str, Any],
    stock: Mapping[str, Any],
    initial_stock: int,
    quantity_per_order: int,
    pending_outbox_counts: Mapping[str, int],
    coupon_code: str,
    expected_discount_amount: int,
    expected_payable_amount: int,
) -> list[str]:
    errors: list[str] = []
    expected_available = initial_stock - quantity_per_order

    if not is_order_state(card_order, "CONFIRMED", "COMPLETED"):
        errors.append(
            "CARD order expected CONFIRMED/COMPLETED; "
            f"got {card_order.get('status')}/{card_order.get('sagaStatus')}"
        )
    if card_order.get("couponCode") != coupon_code:
        errors.append(f"CARD order coupon expected {coupon_code}, got {card_order.get('couponCode')}")
    if not numeric_equals(card_order.get("discountAmount"), expected_discount_amount):
        errors.append(
            "CARD order discountAmount expected "
            f"{expected_discount_amount}, got {card_order.get('discountAmount')}"
        )
    if not numeric_equals(card_order.get("payableAmount"), expected_payable_amount):
        errors.append(
            "CARD order payableAmount expected "
            f"{expected_payable_amount}, got {card_order.get('payableAmount')}"
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


def validate_coupon_quote_result(
    *,
    quote: Mapping[str, Any],
    coupon_code: str,
    expected_discount_amount: int,
    expected_payable_amount: int,
) -> list[str]:
    errors: list[str] = []
    if quote.get("couponCode") != coupon_code:
        errors.append(f"quote coupon expected {coupon_code}, got {quote.get('couponCode')}")
    if quote.get("applied") is not True:
        errors.append(f"quote applied expected True, got {quote.get('applied')} with reason {quote.get('reason')}")
    if not numeric_equals(quote.get("discountAmount"), expected_discount_amount):
        errors.append(
            "quote discountAmount expected "
            f"{expected_discount_amount}, got {quote.get('discountAmount')}"
        )
    if not numeric_equals(quote.get("payAmount"), expected_payable_amount):
        errors.append(
            "quote payAmount expected "
            f"{expected_payable_amount}, got {quote.get('payAmount')}"
        )
    return errors


def is_order_state(order: Mapping[str, Any], status: str, saga_status: str) -> bool:
    return order.get("status") == status and order.get("sagaStatus") == saga_status


def numeric_equals(actual: Any, expected: int) -> bool:
    try:
        return float(actual) == float(expected)
    except (TypeError, ValueError):
        return False


class ApiClient:
    def __init__(self, timeout_seconds: float = 30.0) -> None:
        self.timeout_seconds = timeout_seconds

    def get(self, url: str, headers: Mapping[str, str] | None = None) -> Mapping[str, Any]:
        return self.request("GET", url, headers=headers)

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
            try:
                error_payload = json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                error_payload = {}
            raise ApiHttpError(error.code, method, url, error_payload, raw) from error
        except urllib.error.URLError as error:
            reason = getattr(error, "reason", error)
            raise ApiConnectionError(method, url, str(reason)) from error
        return json.loads(raw) if raw else {}


class ApiConnectionError(RuntimeError):
    def __init__(self, method: str, url: str, reason: str) -> None:
        super().__init__(f"{method} {url} failed: {reason}")


class ApiHttpError(RuntimeError):
    def __init__(
        self,
        status_code: int,
        method: str,
        url: str,
        payload: Mapping[str, Any],
        raw: str,
    ) -> None:
        super().__init__(f"HTTP {status_code} {method} {url}: {raw}")
        self.status_code = status_code
        self.payload = payload

    @property
    def error_code(self) -> str | None:
        error = self.payload.get("error")
        if isinstance(error, Mapping):
            code = error.get("code")
            return str(code) if code is not None else None
        return None


def response_data(payload: Mapping[str, Any]) -> Any:
    return payload.get("data", payload)


def parse_utc_instant(value: str) -> datetime:
    normalized = value.strip()
    if normalized.endswith("Z"):
        normalized = f"{normalized[:-1]}+00:00"
    return datetime.fromisoformat(normalized).astimezone(timezone.utc)


def retryable_pending_outbox_items(
    items: Sequence[Mapping[str, Any]],
    *,
    now: datetime | None = None,
) -> list[Mapping[str, Any]]:
    reference_time = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    retryable: list[Mapping[str, Any]] = []
    for item in items:
        if item.get("status") != "PENDING":
            continue
        next_retry_at = item.get("nextRetryAt")
        if not next_retry_at or parse_utc_instant(str(next_retry_at)) <= reference_time:
            retryable.append(item)
    return retryable


def admin_auth_headers(config: ScenarioConfig) -> dict[str, str]:
    token = config.admin_bearer_token
    if not token:
        return {}
    return {"Authorization": f"Bearer {token}"}


def outbox_recovery_snapshot_from_items(
    items_by_service: Mapping[str, Sequence[Mapping[str, Any]]],
    *,
    now: datetime | None = None,
) -> dict[str, Any]:
    pending_counts: dict[str, int] = {}
    retryable_pending_counts: dict[str, int] = {}
    deferred_pending_counts: dict[str, int] = {}
    failed_counts: dict[str, int] = {}
    retryable_pending_event_ids: dict[str, list[str]] = {}
    deferred_pending_event_ids: dict[str, list[str]] = {}
    failed_event_ids: dict[str, list[str]] = {}

    for service in SERVICE_BASES:
        items = list(items_by_service.get(service, []))
        pending_items = [item for item in items if item.get("status") == "PENDING"]
        failed_items = [item for item in items if item.get("status") == "FAILED"]
        retryable_items = retryable_pending_outbox_items(pending_items, now=now)
        retryable_ids = {id(item) for item in retryable_items}
        deferred_items = [item for item in pending_items if id(item) not in retryable_ids]

        pending_counts[service] = len(pending_items)
        retryable_pending_counts[service] = len(retryable_items)
        deferred_pending_counts[service] = len(deferred_items)
        failed_counts[service] = len(failed_items)
        retryable_pending_event_ids[service] = event_ids_from_items(retryable_items)
        deferred_pending_event_ids[service] = event_ids_from_items(deferred_items)
        failed_event_ids[service] = event_ids_from_items(failed_items)

    return {
        "pendingCounts": pending_counts,
        "retryablePendingCounts": retryable_pending_counts,
        "deferredPendingCounts": deferred_pending_counts,
        "failedCounts": failed_counts,
        "retryablePendingEventIds": retryable_pending_event_ids,
        "deferredPendingEventIds": deferred_pending_event_ids,
        "failedEventIds": failed_event_ids,
    }


def event_ids_from_items(items: Sequence[Mapping[str, Any]]) -> list[str]:
    return sorted(str(item.get("eventId")) for item in items if item.get("eventId"))


def validate_outbox_recovery_result(
    before: Mapping[str, Any],
    after: Mapping[str, Any],
) -> list[str]:
    errors: list[str] = []
    retryable_pending = positive_pending_outbox_changes(after.get("retryablePendingCounts", {}))
    failed = positive_pending_outbox_changes(after.get("failedCounts", {}))
    if retryable_pending:
        errors.append(f"retryable pending outbox remains: {retryable_pending}")
    if failed:
        errors.append(f"failed outbox remains: {failed}")
    return errors


def kafka_outage_observation_statuses() -> tuple[str, ...]:
    return KAFKA_OUTAGE_OBSERVATION_STATUSES


def validate_kafka_outage_recovery_result(
    *,
    paused_order: Mapping[str, Any],
    paused_pending_outbox_counts: Mapping[str, int],
    paused_new_pending_outbox_event_ids: Mapping[str, Sequence[str]],
    final_order: Mapping[str, Any],
    final_stock: Mapping[str, Any],
    initial_stock: int,
    quantity_per_order: int,
    final_pending_outbox_counts: Mapping[str, int],
    final_new_pending_outbox_event_ids: Mapping[str, Sequence[str]],
) -> list[str]:
    errors: list[str] = []
    if is_order_state(paused_order, "CONFIRMED", "COMPLETED") or is_order_state(paused_order, "CANCELLED", "FAILED"):
        errors.append(
            "order settled while kafka was paused: "
            f"{paused_order.get('status')}/{paused_order.get('sagaStatus')}"
        )

    paused_pending = positive_pending_outbox_changes(paused_pending_outbox_counts)
    paused_new_pending = {
        name: list(ids)
        for name, ids in paused_new_pending_outbox_event_ids.items()
        if ids
    }
    if not paused_pending and not paused_new_pending:
        errors.append("pending outbox was not observed while kafka was paused")

    if not is_order_state(final_order, "CONFIRMED", "COMPLETED"):
        errors.append(
            "final order expected CONFIRMED/COMPLETED; "
            f"got {final_order.get('status')}/{final_order.get('sagaStatus')}"
        )

    expected_available = initial_stock - quantity_per_order
    if final_stock.get("availableQuantity") != expected_available or final_stock.get("reservedQuantity") != 0:
        errors.append(
            "stock expected "
            f"availableQuantity={expected_available}, reservedQuantity=0; "
            f"got availableQuantity={final_stock.get('availableQuantity')}, "
            f"reservedQuantity={final_stock.get('reservedQuantity')}"
        )

    final_pending = positive_pending_outbox_changes(final_pending_outbox_counts)
    if final_pending:
        errors.append(f"final pending outbox remains: {final_pending}")

    final_new_pending = {
        name: list(ids)
        for name, ids in final_new_pending_outbox_event_ids.items()
        if ids
    }
    if final_new_pending:
        errors.append(f"final new pending outbox events remain: {final_new_pending}")
    return errors


def count_pending_outbox(client: ApiClient, config: ScenarioConfig) -> dict[str, int]:
    return count_outbox(client, config, statuses=("PENDING",))


def count_outbox(
    client: ApiClient,
    config: ScenarioConfig,
    *,
    statuses: Sequence[str],
) -> dict[str, int]:
    counts: dict[str, int] = {}
    for name in SERVICE_BASES:
        payload = response_data(
            client.get(outbox_list_url(config, name, statuses=statuses), headers=admin_auth_headers(config))
        )
        counts[name] = len(payload.get("items", []))
    return counts


def pending_outbox_event_ids(client: ApiClient, config: ScenarioConfig) -> dict[str, set[str]]:
    return outbox_event_ids(client, config, statuses=("PENDING",))


def outbox_event_ids(
    client: ApiClient,
    config: ScenarioConfig,
    *,
    statuses: Sequence[str],
) -> dict[str, set[str]]:
    event_ids: dict[str, set[str]] = {}
    for name in SERVICE_BASES:
        payload = response_data(
            client.get(outbox_list_url(config, name, statuses=statuses), headers=admin_auth_headers(config))
        )
        event_ids[name] = {str(item.get("eventId")) for item in payload.get("items", []) if item.get("eventId")}
    return event_ids


def outbox_items(
    client: ApiClient,
    config: ScenarioConfig,
    service: str,
    *,
    statuses: Sequence[str] = ("PENDING",),
) -> list[Mapping[str, Any]]:
    payload = response_data(
        client.get(outbox_list_url(config, service, statuses=statuses), headers=admin_auth_headers(config))
    )
    return list(payload.get("items", []))


def outbox_recovery_snapshot(client: ApiClient, config: ScenarioConfig) -> dict[str, Any]:
    items_by_service = {
        service: outbox_items(client, config, service, statuses=("PENDING", "FAILED"))
        for service in SERVICE_BASES
    }
    return outbox_recovery_snapshot_from_items(items_by_service)


def outbox_list_url(
    config: ScenarioConfig,
    service: str,
    *,
    statuses: Sequence[str] = ("PENDING",),
) -> str:
    status_param = ",".join(statuses)
    return (
        f"{config.outbox_api_url}/api/admin/outbox-services/{service}/events"
        f"?status={status_param}&limit={OUTBOX_QUERY_LIMIT}"
    )


def outbox_retry_url(config: ScenarioConfig, service: str) -> str:
    return (
        f"{config.outbox_api_url}/api/admin/outbox-services/{service}/events/retry"
        f"?batchSize={config.relay_batch_size}"
    )


def outbox_requeue_failed_url(config: ScenarioConfig, service: str) -> str:
    return (
        f"{config.outbox_api_url}/api/admin/outbox-services/{service}/events/failed/requeue"
        f"?batchSize={config.relay_batch_size}"
    )


def pending_outbox_delta(before: Mapping[str, int], after: Mapping[str, int]) -> dict[str, int]:
    return {name: after.get(name, 0) - before.get(name, 0) for name in SERVICE_BASES}


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
        config.promotion_url,
    ]):
        payload = client.get(f"{base_url}/actuator/health")
        if payload.get("status") != "UP":
            raise RuntimeError(f"service is not healthy: {base_url} -> {payload}")


def outbox_recovery_healthcheck(client: ApiClient, config: ScenarioConfig) -> None:
    for base_url in dict.fromkeys([
        config.outbox_api_url,
        config.order_url,
        config.inventory_url,
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
        maybe_relay_wave(client, config)
        final_orders = [get_order(client, config, order["orderId"]) for order in orders]
        final_stock = get_stock(client, config, sku_id)
        pending_after = count_pending_outbox(client, config)
        pending_delta = pending_outbox_delta(pending_before, pending_after)
        if (
            not summarize_orders(final_orders).unresolved_order_ids
            and not positive_pending_outbox_changes(pending_delta)
        ):
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


def run_burst_idempotency_scenario(config: ScenarioConfig) -> Mapping[str, Any]:
    client = ApiClient(timeout_seconds=120.0)
    product_code, sku_id = scenario_ids(config.prefix)

    healthcheck(client, config)
    ensure_no_pending_outbox(client, config)
    pending_before = count_pending_outbox(client, config)
    pending_ids_before = pending_outbox_event_ids(client, config)
    seed_product(client, config, product_code)
    seed_stock(client, config, product_code, sku_id)
    attempts = create_order_attempts_with_idempotency_replays(client, config, product_code, sku_id)
    unique_orders = unique_orders_from_attempts(attempts)
    unique_order_ids = [str(order["orderId"]) for order in unique_orders]

    final_orders: list[Mapping[str, Any]] = []
    final_stock: Mapping[str, Any] = {}
    pending_after = pending_before
    pending_delta = pending_outbox_delta(pending_before, pending_after)
    pending_ids_after = pending_ids_before
    pending_new_ids: dict[str, list[str]] = {}
    for _ in range(config.max_attempts):
        maybe_relay_wave_concurrently(client, config)
        final_orders = [get_order(client, config, order_id) for order_id in unique_order_ids]
        final_stock = get_stock(client, config, sku_id)
        pending_after = count_pending_outbox(client, config)
        pending_delta = pending_outbox_delta(pending_before, pending_after)
        pending_ids_after = pending_outbox_event_ids(client, config)
        pending_new_ids = new_pending_outbox_event_ids(pending_ids_before, pending_ids_after)
        if (
            not summarize_orders(final_orders).unresolved_order_ids
            and not positive_pending_outbox_changes(pending_delta)
            and not pending_new_ids
        ):
            break
        time.sleep(config.wait_seconds)
    else:
        final_orders = [get_order(client, config, order_id) for order_id in unique_order_ids]
        final_stock = get_stock(client, config, sku_id)
        pending_after = count_pending_outbox(client, config)
        pending_delta = pending_outbox_delta(pending_before, pending_after)
        pending_ids_after = pending_outbox_event_ids(client, config)
        pending_new_ids = new_pending_outbox_event_ids(pending_ids_before, pending_ids_after)

    post_replay_orders: list[Mapping[str, Any]] = []
    post_replay_stock: Mapping[str, Any] = {}
    post_replay_pending_after = pending_after
    post_replay_pending_delta = pending_outbox_delta(pending_before, post_replay_pending_after)
    post_replay_pending_ids_after = pending_ids_after
    post_replay_new_pending_ids = pending_new_ids
    for _ in range(config.stability_waves):
        maybe_relay_wave_concurrently(client, config)
        time.sleep(config.wait_seconds)

    post_replay_orders = [get_order(client, config, order_id) for order_id in unique_order_ids]
    post_replay_stock = get_stock(client, config, sku_id)
    post_replay_pending_after = count_pending_outbox(client, config)
    post_replay_pending_delta = pending_outbox_delta(pending_before, post_replay_pending_after)
    post_replay_pending_ids_after = pending_outbox_event_ids(client, config)
    post_replay_new_pending_ids = new_pending_outbox_event_ids(pending_ids_before, post_replay_pending_ids_after)
    replay_order_ids = order_ids_by_request_index(attempts)

    errors = validate_burst_idempotency_result(
        orders=final_orders,
        stock=final_stock,
        initial_stock=config.initial_stock,
        quantity_per_order=config.quantity_per_order,
        order_count=config.order_count,
        pending_outbox_counts=pending_delta,
        new_pending_outbox_event_ids=pending_new_ids,
        replay_order_ids_by_index=replay_order_ids,
        request_attempt_count=len(attempts),
        idempotency_replays=config.idempotency_replays,
        post_replay_orders=post_replay_orders,
        post_replay_stock=post_replay_stock,
        post_replay_pending_outbox_counts=post_replay_pending_delta,
        post_replay_new_pending_outbox_event_ids=post_replay_new_pending_ids,
    )

    return {
        "productCode": product_code,
        "skuId": sku_id,
        "requestAttemptCount": len(attempts),
        "orderIds": unique_order_ids,
        "idempotencyReplayOrderIdsByIndex": replay_order_ids,
        "orders": final_orders,
        "stock": final_stock,
        "pendingOutboxBaseline": pending_before,
        "pendingOutboxCounts": pending_after,
        "pendingOutboxDelta": pending_delta,
        "pendingOutboxNewEventIds": pending_new_ids,
        "postReplayOrders": post_replay_orders,
        "postReplayStock": post_replay_stock,
        "postReplayPendingOutboxCounts": post_replay_pending_after,
        "postReplayPendingOutboxDelta": post_replay_pending_delta,
        "postReplayPendingOutboxNewEventIds": post_replay_new_pending_ids,
        "summary": summarize_orders(final_orders).__dict__,
        "postReplaySummary": summarize_orders(post_replay_orders).__dict__,
        "idempotencyReplays": config.idempotency_replays,
        "relayWorkers": config.relay_workers,
        "stabilityWaves": config.stability_waves,
        "errors": errors,
    }


def run_demo_order_flow_scenario(config: ScenarioConfig) -> Mapping[str, Any]:
    client = ApiClient(timeout_seconds=90.0)
    product_code, sku_id = scenario_ids(config.prefix)
    coupon_code = coupon_code_for_product(product_code)
    expected_discount_amount = demo_coupon_discount_amount(config)
    expected_payable_amount = demo_coupon_payable_amount(config)

    healthcheck(client, config)
    ensure_no_pending_outbox(client, config)
    pending_before = count_pending_outbox(client, config)
    seed_product(client, config, product_code)
    seed_stock(client, config, product_code, sku_id)
    seed_coupon(client, config, coupon_code)
    coupon_quote = quote_coupon(client, config, coupon_code, demo_order_amount(config))

    card_order = create_order(client, config, product_code, sku_id, "CARD", "member-demo-card", 1, coupon_code)
    fail_order = create_order(client, config, product_code, sku_id, "FAIL_CARD", "member-demo-fail", 2)
    delay_order = create_order(client, config, product_code, sku_id, "DELAY_CARD", "member-demo-delay", 3)

    orders = {
        "card": card_order,
        "fail": fail_order,
        "delay": delay_order,
    }
    pending_after = pending_before
    for _ in range(config.max_attempts):
        maybe_relay_wave(client, config)
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
        maybe_relay_wave(client, config)
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
            and not positive_pending_outbox_changes(pending_delta)
        ):
            break
        time.sleep(config.wait_seconds)
    else:
        final_stock = get_stock(client, config, sku_id)
        pending_after = count_pending_outbox(client, config)
        pending_delta = pending_outbox_delta(pending_before, pending_after)

    errors = validate_coupon_quote_result(
        quote=coupon_quote,
        coupon_code=coupon_code,
        expected_discount_amount=expected_discount_amount,
        expected_payable_amount=expected_payable_amount,
    )
    errors.extend(validate_demo_order_flow_result(
        card_order=orders["card"],
        fail_order=orders["fail"],
        delay_order=orders["delay"],
        stock=final_stock,
        initial_stock=config.initial_stock,
        quantity_per_order=config.quantity_per_order,
        pending_outbox_counts=pending_delta,
        coupon_code=coupon_code,
        expected_discount_amount=expected_discount_amount,
        expected_payable_amount=expected_payable_amount,
    ))

    return {
        "productCode": product_code,
        "skuId": sku_id,
        "couponCode": coupon_code,
        "couponQuote": coupon_quote,
        "orders": orders,
        "stock": final_stock,
        "pendingOutboxBaseline": pending_before,
        "pendingOutboxCounts": pending_after,
        "pendingOutboxDelta": pending_delta,
        "errors": errors,
    }


def run_outbox_recovery_scenario(config: ScenarioConfig) -> Mapping[str, Any]:
    client = ApiClient(timeout_seconds=90.0)
    correlation_id = f"corr-outbox-recovery-{uuid4().hex[:12]}"

    outbox_recovery_healthcheck(client, config)
    before = outbox_recovery_snapshot(client, config)
    after = before
    actions: list[Mapping[str, Any]] = []

    for attempt in range(1, config.max_attempts + 1):
        services: dict[str, dict[str, Any]] = {}
        for service in SERVICE_BASES:
            service_actions: dict[str, Any] = {}
            headers = outbox_recovery_headers(config, correlation_id, attempt, service)
            if config.requeue_failed:
                service_actions["requeueFailed"] = response_data(
                    client.post(outbox_requeue_failed_url(config, service), headers=headers)
                )
            service_actions["retryPending"] = response_data(
                client.post(outbox_retry_url(config, service), headers=headers)
            )
            services[service] = service_actions
        actions.append({"attempt": attempt, "services": services})

        if config.wait_seconds:
            time.sleep(config.wait_seconds)
        after = outbox_recovery_snapshot(client, config)
        if not validate_outbox_recovery_result(before, after):
            break

    errors = validate_outbox_recovery_result(before, after)
    return {
        "operatorId": config.operator_id,
        "correlationId": correlation_id,
        "requeueFailed": config.requeue_failed,
        "attempts": actions,
        "before": before,
        "after": after,
        "errors": errors,
    }


def run_kafka_outage_recovery_scenario(config: ScenarioConfig) -> Mapping[str, Any]:
    client = ApiClient(timeout_seconds=120.0)
    product_code, sku_id = scenario_ids(config.prefix)

    healthcheck(client, config)
    ensure_no_pending_outbox(client, config)
    outage_counts_before = count_outbox(client, config, statuses=kafka_outage_observation_statuses())
    outage_ids_before = outbox_event_ids(client, config, statuses=kafka_outage_observation_statuses())
    seed_product(client, config, product_code)
    seed_stock(client, config, product_code, sku_id)

    initial_order: Mapping[str, Any] = {}
    paused_order: Mapping[str, Any] = {}
    paused_pending_after = outage_counts_before
    paused_pending_delta = pending_outbox_delta(outage_counts_before, paused_pending_after)
    paused_new_pending_ids: dict[str, list[str]] = {}
    final_order: Mapping[str, Any] = {}
    final_stock: Mapping[str, Any] = {}
    final_pending_after = outage_counts_before
    final_pending_delta = pending_outbox_delta(outage_counts_before, final_pending_after)
    final_new_pending_ids: dict[str, list[str]] = {}
    paused = False

    try:
        run_docker_compose_action(config, "pause")
        paused = True
        initial_order = create_order(client, config, product_code, sku_id, "CARD", "member-kafka-outage", 1)
        order_id = require_order_id(initial_order)
        observed = observe_kafka_outage_state(
            client,
            config,
            order_id,
            outage_counts_before,
            outage_ids_before,
        )
        paused_order = observed["order"]
        paused_pending_after = observed["pendingOutboxCounts"]
        paused_pending_delta = observed["pendingOutboxDelta"]
        paused_new_pending_ids = observed["newPendingOutboxEventIds"]
    finally:
        if paused:
            run_docker_compose_action(config, "unpause")

    order_id = require_order_id(initial_order)
    final_pending_ids_after = outage_ids_before
    for _ in range(config.max_attempts):
        maybe_relay_wave(client, config)
        final_order = get_order(client, config, order_id)
        final_stock = get_stock(client, config, sku_id)
        final_pending_after = count_outbox(client, config, statuses=kafka_outage_observation_statuses())
        final_pending_delta = pending_outbox_delta(outage_counts_before, final_pending_after)
        final_pending_ids_after = outbox_event_ids(client, config, statuses=kafka_outage_observation_statuses())
        final_new_pending_ids = new_pending_outbox_event_ids(outage_ids_before, final_pending_ids_after)
        if (
            is_order_state(final_order, "CONFIRMED", "COMPLETED")
            and final_stock.get("reservedQuantity") == 0
            and not positive_pending_outbox_changes(final_pending_delta)
            and not final_new_pending_ids
        ):
            break
        time.sleep(config.wait_seconds)
    else:
        final_order = get_order(client, config, order_id)
        final_stock = get_stock(client, config, sku_id)
        final_pending_after = count_outbox(client, config, statuses=kafka_outage_observation_statuses())
        final_pending_delta = pending_outbox_delta(outage_counts_before, final_pending_after)
        final_pending_ids_after = outbox_event_ids(client, config, statuses=kafka_outage_observation_statuses())
        final_new_pending_ids = new_pending_outbox_event_ids(outage_ids_before, final_pending_ids_after)

    errors = validate_kafka_outage_recovery_result(
        paused_order=paused_order,
        paused_pending_outbox_counts=paused_pending_delta,
        paused_new_pending_outbox_event_ids=paused_new_pending_ids,
        final_order=final_order,
        final_stock=final_stock,
        initial_stock=config.initial_stock,
        quantity_per_order=config.quantity_per_order,
        final_pending_outbox_counts=final_pending_delta,
        final_new_pending_outbox_event_ids=final_new_pending_ids,
    )

    return {
        "productCode": product_code,
        "skuId": sku_id,
        "orderId": order_id,
        "pausedOrder": paused_order,
        "pausedPendingOutboxCounts": paused_pending_after,
        "pausedPendingOutboxDelta": paused_pending_delta,
        "pausedPendingOutboxNewEventIds": paused_new_pending_ids,
        "finalOrder": final_order,
        "finalStock": final_stock,
        "finalPendingOutboxCounts": final_pending_after,
        "finalPendingOutboxDelta": final_pending_delta,
        "finalPendingOutboxNewEventIds": final_new_pending_ids,
        "kafkaService": config.kafka_service,
        "composeFile": config.compose_file,
        "errors": errors,
    }


def require_order_id(order: Mapping[str, Any]) -> str:
    order_id = order.get("orderId")
    if not order_id:
        raise RuntimeError(f"order response did not include orderId: {order}")
    return str(order_id)


def observe_kafka_outage_state(
    client: ApiClient,
    config: ScenarioConfig,
    order_id: str,
    outbox_counts_before: Mapping[str, int],
    outbox_ids_before: Mapping[str, set[str]],
) -> dict[str, Any]:
    deadline = time.monotonic() + config.outage_observation_seconds
    latest_order: Mapping[str, Any] = {}
    latest_counts = dict(outbox_counts_before)
    latest_delta = pending_outbox_delta(outbox_counts_before, latest_counts)
    latest_new_ids: dict[str, list[str]] = {}

    for attempt in range(config.max_attempts):
        latest_order = get_order(client, config, order_id)
        latest_counts = count_outbox(client, config, statuses=kafka_outage_observation_statuses())
        latest_delta = pending_outbox_delta(outbox_counts_before, latest_counts)
        latest_ids = outbox_event_ids(client, config, statuses=kafka_outage_observation_statuses())
        latest_new_ids = new_pending_outbox_event_ids(outbox_ids_before, latest_ids)
        if (
            positive_pending_outbox_changes(latest_delta)
            or latest_new_ids
            or is_order_state(latest_order, "CONFIRMED", "COMPLETED")
            or is_order_state(latest_order, "CANCELLED", "FAILED")
        ):
            break
        if config.outage_observation_seconds and time.monotonic() >= deadline:
            break
        if attempt < config.max_attempts - 1 and config.wait_seconds:
            time.sleep(config.wait_seconds)

    return {
        "order": latest_order,
        "pendingOutboxCounts": latest_counts,
        "pendingOutboxDelta": latest_delta,
        "newPendingOutboxEventIds": latest_new_ids,
    }


def run_docker_compose_action(config: ScenarioConfig, action: str) -> None:
    if action not in {"pause", "unpause"}:
        raise ValueError(f"unsupported compose action: {action}")
    if not config.compose_file:
        raise RuntimeError("--compose-file is required for kafka outage recovery")
    if not config.env_file:
        raise RuntimeError("--env-file is required for kafka outage recovery")

    command = [
        "docker",
        "compose",
        "--env-file",
        config.env_file,
        "-f",
        config.compose_file,
        action,
        config.kafka_service,
    ]
    try:
        subprocess.run(command, check=True, capture_output=True, text=True)
    except subprocess.CalledProcessError as error:
        output = (error.stderr or error.stdout or "").strip()
        suffix = f": {output}" if output else ""
        raise RuntimeError(f"docker compose {action} {config.kafka_service} failed{suffix}") from error


def outbox_recovery_headers(
    config: ScenarioConfig,
    correlation_id: str,
    attempt: int,
    service: str,
) -> dict[str, str]:
    headers = {
        "X-Operator-Id": config.operator_id,
        "X-Correlation-Id": f"{correlation_id}-{attempt:02d}-{service}",
    }
    headers.update(admin_auth_headers(config))
    return headers


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


def seed_coupon(client: ApiClient, config: ScenarioConfig, coupon_code: str) -> Mapping[str, Any]:
    starts_at, ends_at = demo_coupon_period()
    return response_data(
        client.post(
            f"{config.promotion_url}/api/admin/coupons",
            {
                "couponCode": coupon_code,
                "name": "Demo E2E Coupon",
                "discountType": "FIXED_AMOUNT",
                "discountValue": DEMO_COUPON_DISCOUNT_AMOUNT,
                "minOrderAmount": 0,
                "maxDiscountAmount": None,
                "status": "ACTIVE",
                "startsAt": starts_at,
                "endsAt": ends_at,
            },
            headers={
                "Idempotency-Key": f"idem-coupon-{coupon_code}",
                "X-Correlation-Id": f"corr-coupon-{coupon_code}",
            },
        )
    )


def quote_coupon(client: ApiClient, config: ScenarioConfig, coupon_code: str, order_amount: int) -> Mapping[str, Any]:
    return response_data(
        client.post(
            f"{config.order_api_url}/api/coupons/quote",
            {
                "couponCode": coupon_code,
                "orderAmount": order_amount,
            },
            headers={"X-Correlation-Id": f"corr-coupon-quote-{coupon_code}"},
        )
    )


def create_order(
    client: ApiClient,
    config: ScenarioConfig,
    product_code: str,
    sku_id: str,
    payment_method: str,
    member_id: str,
    index: int,
    coupon_code: str | None = None,
) -> Mapping[str, Any]:
    body: dict[str, Any] = {
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
    }
    if coupon_code:
        body["couponCode"] = coupon_code

    return response_data(
        client.post(
            f"{config.order_api_url}/api/orders",
            body,
            headers={"Idempotency-Key": f"idem-order-{product_code}-{index:02d}-{payment_method}"},
        )
    )


def cancel_delayed_order(client: ApiClient, config: ScenarioConfig, order_id: str, product_code: str) -> None:
    headers = {
        "Idempotency-Key": f"idem-admin-cancel-{product_code}-{order_id}",
    }
    headers.update(admin_auth_headers(config))
    client.post(
        f"{config.order_api_url}/api/admin/orders/{order_id}/cancel",
        headers=headers,
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


def create_order_attempts_with_idempotency_replays(
    client: ApiClient,
    config: ScenarioConfig,
    product_code: str,
    sku_id: str,
) -> list[Mapping[str, Any]]:
    def create_attempt(index: int, replay: int) -> Mapping[str, Any]:
        idempotency_key = f"idem-order-{product_code}-{index:02d}"
        payload = response_data(
            create_order_with_retryable_replay_pending(
                client,
                config,
                product_code,
                sku_id,
                index,
                replay,
                idempotency_key,
            )
        )
        return {
            "index": index,
            "replay": replay,
            "idempotencyKey": idempotency_key,
            "orderId": str(payload["orderId"]),
            "order": payload,
        }

    attempts: list[Mapping[str, Any]] = []
    task_count = config.order_count * config.idempotency_replays
    max_workers = min(task_count, MAX_BURST_WORKERS)
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = [
            executor.submit(create_attempt, index, replay)
            for index in range(1, config.order_count + 1)
            for replay in range(1, config.idempotency_replays + 1)
        ]
        for future in as_completed(futures):
            attempts.append(future.result())
    return sorted(attempts, key=lambda attempt: (int(attempt["index"]), int(attempt["replay"])))


def create_order_with_retryable_replay_pending(
    client: ApiClient,
    config: ScenarioConfig,
    product_code: str,
    sku_id: str,
    index: int,
    replay: int,
    idempotency_key: str,
) -> Mapping[str, Any]:
    for attempt in range(1, config.max_attempts + 1):
        try:
            return client.post(
                f"{config.order_api_url}/api/orders",
                {
                    "memberId": f"member-burst-{index:02d}",
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
                headers={
                    "Idempotency-Key": idempotency_key,
                    "X-Correlation-Id": f"corr-burst-{product_code}-{index:02d}-{replay:02d}-{attempt:02d}",
                },
            )
        except ApiHttpError as error:
            if error.status_code == 409 and error.error_code == "ORDER_IDEMPOTENCY_REPLAY_PENDING":
                time.sleep(config.wait_seconds)
                continue
            raise
    raise RuntimeError(f"idempotent replay remained pending after {config.max_attempts} attempts: {idempotency_key}")


def relay_wave(client: ApiClient, config: ScenarioConfig) -> None:
    relay_order = ["order", "inventory", "order", "payment", "order", "inventory"]
    for service in relay_order:
        client.post(outbox_retry_url(config, service), headers=admin_auth_headers(config))
        time.sleep(config.wait_seconds)


def relay_wave_concurrently(client: ApiClient, config: ScenarioConfig) -> None:
    relay_order = ["order", "inventory", "order", "payment", "order", "inventory"]

    def retry_all_services() -> None:
        for service in relay_order:
            client.post(outbox_retry_url(config, service), headers=admin_auth_headers(config))

    with ThreadPoolExecutor(max_workers=config.relay_workers) as executor:
        futures = [executor.submit(retry_all_services) for _ in range(config.relay_workers)]
        for future in as_completed(futures):
            future.result()


def maybe_relay_wave(client: ApiClient, config: ScenarioConfig) -> None:
    if config.relay_mode == "automatic":
        return
    relay_wave(client, config)


def maybe_relay_wave_concurrently(client: ApiClient, config: ScenarioConfig) -> None:
    if config.relay_mode == "automatic":
        return
    relay_wave_concurrently(client, config)


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
    command_parser.add_argument(
        "--promotion-url",
        default=DEFAULT_PROMOTION_URL,
        help="Promotion Service admin URL. Coupon quote still runs through --order-api-url.",
    )
    command_parser.add_argument("--orders", type=positive_int, default=default_orders)
    command_parser.add_argument("--initial-stock", type=non_negative_int, default=default_initial_stock)
    command_parser.add_argument("--quantity", type=positive_int, default=1)
    command_parser.add_argument("--unit-price", type=positive_int, default=12000)
    command_parser.add_argument("--prefix", type=scenario_prefix, default=default_prefix)
    command_parser.add_argument("--max-attempts", type=positive_int, default=12)
    command_parser.add_argument("--relay-batch-size", type=relay_batch_size, default=100)
    command_parser.add_argument("--wait-seconds", type=non_negative_float, default=0.5)
    command_parser.add_argument(
        "--relay-mode",
        choices=("manual", "automatic"),
        default="manual",
        help="manual calls admin retry APIs; automatic only waits for service schedulers.",
    )
    command_parser.add_argument(
        "--allow-existing-pending",
        action="store_true",
        help="Skip the preflight failure when existing PENDING outbox rows are present.",
    )
    command_parser.add_argument(
        "--admin-bearer-token",
        default=os.getenv("STOCKRUSH_ADMIN_BEARER_TOKEN"),
        help="Admin bearer token for Gateway admin routes. Defaults to STOCKRUSH_ADMIN_BEARER_TOKEN.",
    )


def add_outbox_recovery_arguments(command_parser: argparse.ArgumentParser) -> None:
    command_parser.add_argument("--catalog-url", default=DEFAULT_CATALOG_URL)
    command_parser.add_argument("--inventory-url", default=DEFAULT_INVENTORY_URL)
    command_parser.add_argument("--order-url", default=DEFAULT_ORDER_URL, help="Order Service health URL")
    command_parser.add_argument(
        "--order-api-url",
        default=DEFAULT_ORDER_API_URL,
        help="Public order API health URL. Defaults to Gateway at http://localhost:18080.",
    )
    command_parser.add_argument(
        "--outbox-api-url",
        default=DEFAULT_OUTBOX_API_URL,
        help="Outbox admin routing URL. Defaults to Gateway at http://localhost:18080.",
    )
    command_parser.add_argument("--payment-url", default=DEFAULT_PAYMENT_URL)
    command_parser.add_argument("--promotion-url", default=DEFAULT_PROMOTION_URL)
    command_parser.add_argument("--max-attempts", type=positive_int, default=3)
    command_parser.add_argument("--relay-batch-size", type=relay_batch_size, default=100)
    command_parser.add_argument("--wait-seconds", type=non_negative_float, default=1.0)
    command_parser.add_argument(
        "--operator-id",
        type=non_blank_text,
        default="local-e2e",
        help="Operator id stored in outbox admin action audit rows.",
    )
    command_parser.add_argument(
        "--skip-requeue-failed",
        action="store_true",
        help="Retry pending rows only. FAILED rows remain reported as recovery errors.",
    )
    command_parser.add_argument(
        "--admin-bearer-token",
        default=os.getenv("STOCKRUSH_ADMIN_BEARER_TOKEN"),
        help="Admin bearer token for Gateway admin routes. Defaults to STOCKRUSH_ADMIN_BEARER_TOKEN.",
    )


def add_kafka_outage_recovery_arguments(command_parser: argparse.ArgumentParser) -> None:
    add_runtime_arguments(
        command_parser,
        default_orders=1,
        default_initial_stock=3,
        default_prefix="KAFKA-OUTAGE-E2E",
    )
    command_parser.add_argument("--compose-file", default="infra/demo/docker-compose.yml")
    command_parser.add_argument("--env-file", default="infra/demo/.env")
    command_parser.add_argument("--kafka-service", type=non_blank_text, default="kafka")
    command_parser.add_argument(
        "--outage-observation-seconds",
        type=non_negative_float,
        default=2.0,
        help="Seconds to observe the order/outbox state while Kafka is paused.",
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

    burst = subparsers.add_parser(
        "burst-idempotency",
        aliases=["idempotent-burst", "outbox-idempotent-burst"],
        help="Run high-volume idempotency replay and concurrent outbox retry E2E",
    )
    add_runtime_arguments(
        burst,
        default_orders=30,
        default_initial_stock=10,
        default_prefix="BURST-E2E",
    )
    burst.add_argument(
        "--idempotency-replays",
        type=positive_int,
        default=2,
        help="Number of same-key create-order attempts per logical order.",
    )
    burst.add_argument(
        "--relay-workers",
        type=positive_int,
        default=4,
        help="Number of concurrent outbox retry workers per relay wave.",
    )
    burst.add_argument(
        "--stability-waves",
        type=positive_int,
        default=2,
        help="Extra concurrent retry waves after the scenario has settled.",
    )

    outbox_recovery = subparsers.add_parser(
        "outbox-recovery",
        aliases=["recover-outbox"],
        help="Run manual retry/requeue waves for pending and failed outbox rows.",
    )
    add_outbox_recovery_arguments(outbox_recovery)

    kafka_outage = subparsers.add_parser(
        "kafka-outage-recovery",
        aliases=["broker-outage-recovery"],
        help="Pause the demo Kafka service, create an order, then unpause and verify recovery.",
    )
    add_kafka_outage_recovery_arguments(kafka_outage)
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
        promotion_url=args.promotion_url.rstrip("/"),
        order_count=getattr(args, "orders", 1),
        initial_stock=getattr(args, "initial_stock", 0),
        quantity_per_order=getattr(args, "quantity", 1),
        unit_price=getattr(args, "unit_price", 12000),
        prefix=getattr(args, "prefix", "OUTBOX-E2E"),
        max_attempts=args.max_attempts,
        relay_batch_size=args.relay_batch_size,
        wait_seconds=args.wait_seconds,
        fail_on_existing_pending=not getattr(args, "allow_existing_pending", True),
        relay_mode=getattr(args, "relay_mode", "manual"),
        idempotency_replays=getattr(args, "idempotency_replays", 1),
        relay_workers=getattr(args, "relay_workers", 1),
        stability_waves=getattr(args, "stability_waves", 1),
        operator_id=getattr(args, "operator_id", "local-e2e"),
        requeue_failed=not getattr(args, "skip_requeue_failed", False),
        compose_file=getattr(args, "compose_file", None),
        env_file=getattr(args, "env_file", None),
        kafka_service=getattr(args, "kafka_service", "kafka"),
        outage_observation_seconds=getattr(args, "outage_observation_seconds", 2.0),
        admin_bearer_token=normalize_admin_bearer_token(getattr(args, "admin_bearer_token", None)),
    )


def main(argv: Sequence[str] | None = None) -> int:
    try:
        args = parse_args(argv or sys.argv[1:])
        if args.command in {"same-sku-concurrency", "concurrent-sku"}:
            result = run_concurrent_sku_scenario(config_from_args(args))
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 1 if result["errors"] else 0
        if args.command in {"demo-order-flow", "cards-smoke"}:
            result = run_demo_order_flow_scenario(config_from_args(args))
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 1 if result["errors"] else 0
        if args.command in {"burst-idempotency", "idempotent-burst", "outbox-idempotent-burst"}:
            result = run_burst_idempotency_scenario(config_from_args(args))
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 1 if result["errors"] else 0
        if args.command in {"outbox-recovery", "recover-outbox"}:
            result = run_outbox_recovery_scenario(config_from_args(args))
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 1 if result["errors"] else 0
        if args.command in {"kafka-outage-recovery", "broker-outage-recovery"}:
            result = run_kafka_outage_recovery_scenario(config_from_args(args))
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 1 if result["errors"] else 0
        raise AssertionError(f"Unsupported command: {args.command}")
    except RuntimeError as error:
        print(json.dumps({"errors": [str(error)]}, ensure_ascii=False, indent=2), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
