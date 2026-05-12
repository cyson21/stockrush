from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "dev_rag.py"
SPEC = importlib.util.spec_from_file_location("dev_rag", MODULE_PATH)
assert SPEC and SPEC.loader
dev_rag = importlib.util.module_from_spec(SPEC)
sys.modules["dev_rag"] = dev_rag
SPEC.loader.exec_module(dev_rag)


class DevRagTest(unittest.TestCase):
    def test_ingest_search_and_context_pack(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "README.md").write_text("# StockRush\n\nKafka order saga platform.", encoding="utf-8")
            (root / "docs" / "adr").mkdir(parents=True)
            (root / "docs" / "adr" / "0001.md").write_text(
                "# Kafka Decision\n\nUse Apache Kafka for order events.",
                encoding="utf-8",
            )
            index = root / ".dev-rag" / "index.sqlite3"

            count = dev_rag.ingest(
                index,
                root,
                [
                    dev_rag.SourceFile(root / "README.md", "project-overview"),
                    dev_rag.SourceFile(root / "docs", "project-doc"),
                ],
            )
            results = dev_rag.search(index, "Kafka order", 5)
            context = dev_rag.generate_context("Implement Kafka order saga", results)

            self.assertEqual(count, 2)
            self.assertTrue(any(result.path == "README.md" for result in results))
            self.assertIn("# Context Pack", context)
            self.assertIn("Implement Kafka order saga", context)
            self.assertIn("docs/adr/0001.md", context)

    def test_excludes_env_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "README.md").write_text("# Visible\n\npublic text", encoding="utf-8")
            (root / ".env").write_text("SECRET=hidden", encoding="utf-8")
            index = root / ".dev-rag" / "index.sqlite3"

            dev_rag.ingest(index, root, [dev_rag.SourceFile(root, "test")])
            results = dev_rag.search(index, "SECRET hidden", 5)

            self.assertEqual(results, [])

    def test_heading_chunks_are_searchable(self) -> None:
        text = "# Root\n\nIntro\n\n## Inventory\n\nReservation TTL and idempotency."

        chunks = dev_rag.split_markdown_chunks(text)

        self.assertTrue(any(heading == "Root > Inventory" for heading, _ in chunks))
        self.assertTrue(any("Reservation TTL" in chunk for _, chunk in chunks))


if __name__ == "__main__":
    unittest.main()
