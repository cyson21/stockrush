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
                "CREATE TABLE order.outbox_events (event_id uuid primary key, payload jsonb);\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertTrue(any(violation.rule_id == "ARCH-004" for violation in violations))

    def test_detects_cross_schema_access(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            java_root = root / "services" / "order-service" / "src" / "main" / "java"
            java_root.mkdir(parents=True)
            (java_root / "OrderQuery.java").write_text(
                'public class OrderQuery { String sql = "select * from inventory.stock"; }\n',
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertTrue(any(violation.rule_id == "ARCH-001" for violation in violations))

    def test_passes_empty_project(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)

            violations = architecture_guard.check(root)

            self.assertEqual(violations, [])


if __name__ == "__main__":
    unittest.main()
