from __future__ import annotations

import asyncio
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

# MCP 서버가 프로젝트 문서/도구 목록을 일관되게 노출하는지 검증합니다.


MODULE_PATH = Path(__file__).resolve().parents[1] / "project_mcp_server.py"
SPEC = importlib.util.spec_from_file_location("project_mcp_server", MODULE_PATH)
assert SPEC and SPEC.loader
project_mcp_server = importlib.util.module_from_spec(SPEC)
sys.modules["project_mcp_server"] = project_mcp_server
SPEC.loader.exec_module(project_mcp_server)


class ProjectMcpServerTest(unittest.TestCase):
    def test_lists_and_reads_project_docs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "README.md").write_text("# Readme\n", encoding="utf-8")
            (root / "TODO.md").write_text("# Tasks\n", encoding="utf-8")
            (root / "docs" / "adr").mkdir(parents=True)
            (root / "docs" / "adr" / "0001.md").write_text("# ADR\n", encoding="utf-8")

            docs = project_mcp_server.list_project_docs(root)
            readme = project_mcp_server.read_project_doc(root, "README.md")

            self.assertIn({"path": "README.md", "type": "markdown"}, docs)
            self.assertIn({"path": "docs/adr/0001.md", "type": "markdown"}, docs)
            self.assertEqual(readme, "# Readme\n")

    def test_rejects_paths_outside_root(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)

            with self.assertRaises(ValueError):
                project_mcp_server.read_project_doc(root, "../outside.md")

    def test_resource_adr_index(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "README.md").write_text("# Readme\n", encoding="utf-8")
            (root / "TODO.md").write_text("# Tasks\n", encoding="utf-8")
            (root / "docs" / "project-tracking.md").parent.mkdir(parents=True)
            (root / "docs" / "project-tracking.md").write_text("# Tracking\n", encoding="utf-8")
            (root / "docs" / "adr").mkdir(parents=True, exist_ok=True)
            (root / "docs" / "adr" / "0001.md").write_text("# ADR\n", encoding="utf-8")

            result = asyncio.run(project_mcp_server.handle_read_resource(root, "stockrush://adr-index"))

            self.assertIn("docs/adr/0001.md", result[0].text)

    def test_tool_list_project_docs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "README.md").write_text("# Readme\n", encoding="utf-8")

            result = asyncio.run(project_mcp_server.handle_call_tool(root, "list_project_docs", {}))

            self.assertIn("README.md", result[0].text)

    def test_project_tool_lists_expected_tools(self) -> None:
        tools = project_mcp_server.build_tool_list()
        tool_names = {tool.name for tool in tools}

        self.assertIn("dev_rag_search", tool_names)
        self.assertIn("architecture_guard_check", tool_names)


if __name__ == "__main__":
    unittest.main()
