from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "architecture_guard.py"
SPEC = importlib.util.spec_from_file_location("architecture_guard", MODULE_PATH)
assert SPEC and SPEC.loader
architecture_guard = importlib.util.module_from_spec(SPEC)
sys.modules["architecture_guard"] = architecture_guard
SPEC.loader.exec_module(architecture_guard)


class ArchitectureGuardTest(unittest.TestCase):
    def test_detects_controller_returning_entity(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            java_root = root / "services" / "catalog-service" / "src" / "main" / "java"
            java_root.mkdir(parents=True)
            (java_root / "Product.java").write_text(
                "import jakarta.persistence.Entity;\n@Entity\npublic class Product {}\n",
                encoding="utf-8",
            )
            (java_root / "ProductController.java").write_text(
                "@RestController\npublic class ProductController {\n"
                "  public Product findProduct() { return new Product(); }\n"
                "}\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertTrue(any(violation.rule_id == "ARCH-002" for violation in violations))

    def test_detects_missing_event_envelope_fields(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            event_root = root / "services" / "order-service" / "src" / "main" / "java"
            event_root.mkdir(parents=True)
            (event_root / "OrderCreatedEvent.java").write_text(
                "public record OrderCreatedEvent(String eventId, String payload) {}\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            event_violations = [violation for violation in violations if violation.rule_id == "ARCH-003"]
            self.assertEqual(len(event_violations), 1)
            self.assertIn("eventType", event_violations[0].message)

    def test_detects_outbox_missing_columns(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            migration_root = root / "services" / "order-service" / "src" / "main" / "resources" / "db"
            migration_root.mkdir(parents=True)
            (migration_root / "V1__outbox.sql").write_text(
                "CREATE TABLE orders.outbox_events (event_id uuid primary key, payload jsonb);\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertTrue(any(violation.rule_id == "ARCH-004" for violation in violations))

    def test_allows_outbox_admin_action_table(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            migration_root = root / "services" / "order-service" / "src" / "main" / "resources" / "db"
            migration_root.mkdir(parents=True)
            (migration_root / "V2__create_outbox_admin_actions.sql").write_text(
                "CREATE TABLE outbox_admin_actions ("
                "id bigserial primary key,"
                "action varchar(50) not null,"
                "affected_count integer not null"
                ");\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertFalse(any(violation.rule_id == "ARCH-004" for violation in violations))

    def test_detects_cross_schema_access(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            java_root = root / "services" / "order-service" / "src" / "main" / "java"
            java_root.mkdir(parents=True)
            (java_root / "OrderQuery.java").write_text(
                'public class OrderQuery {\n'
                '  String paymentSql = "select * from payment.payments";\n'
                '  String inventorySql = "select * from inventory.stock_items";\n'
                '}\n',
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertTrue(any(violation.rule_id == "ARCH-001" for violation in violations))

    def test_detects_direct_read_model_access_from_owner_service(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            java_root = root / "services" / "order-service" / "src" / "main" / "java"
            java_root.mkdir(parents=True)
            (java_root / "OrderSummaryQuery.java").write_text(
                'public class OrderSummaryQuery {\n'
                '  String sql = "select * from read_model.order_summaries";\n'
                '}\n',
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertTrue(any(violation.rule_id == "ARCH-001" for violation in violations))

    def test_allows_kafka_payment_inventory_names_in_order_service(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            java_root = root / "services" / "order-service" / "src" / "main" / "java"
            app_root = java_root / "com" / "stockrush" / "order" / "application"
            kafka_root = java_root / "com" / "stockrush" / "order" / "infra" / "kafka"
            app_root.mkdir(parents=True)
            kafka_root.mkdir(parents=True)
            (app_root / "PaymentAuthorizedPayload.java").write_text(
                "public record PaymentAuthorizedPayload(String paymentId) {}\n",
                encoding="utf-8",
            )
            (app_root / "InventoryReservedPayload.java").write_text(
                "public record InventoryReservedPayload(String reservationId) {}\n",
                encoding="utf-8",
            )
            (kafka_root / "OrderPaymentEventConsumer.java").write_text(
                'public class OrderPaymentEventConsumer {\n'
                '  String topic = "stockrush.payment.events.v1";\n'
                '  String eventName = "payment.authorized";\n'
                '  String eventType = "PaymentAuthorized";\n'
                "  PaymentAuthorizedPayload payload;\n"
                "}\n",
                encoding="utf-8",
            )
            (kafka_root / "OrderInventoryEventConsumer.java").write_text(
                'public class OrderInventoryEventConsumer {\n'
                '  String topic = "stockrush.inventory.events.v1";\n'
                '  String eventName = "inventory.reserved";\n'
                '  String eventType = "InventoryReserved";\n'
                "  InventoryReservedPayload payload;\n"
                "}\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertFalse(any(violation.rule_id == "ARCH-001" for violation in violations))

    def test_passes_empty_project(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)

            violations = architecture_guard.check(root)

            self.assertEqual(violations, [])


if __name__ == "__main__":
    unittest.main()
