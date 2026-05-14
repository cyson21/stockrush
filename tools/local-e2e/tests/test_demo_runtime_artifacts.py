from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


class DemoRuntimeArtifactsTest(unittest.TestCase):
    def test_demo_runtime_files_are_present(self) -> None:
        expected_files = [
            "infra/demo/.env.example",
            "infra/demo/docker-compose.yml",
            "infra/demo/README.md",
            "services/Dockerfile",
            "apps/Dockerfile",
            "apps/nginx/default.conf",
            "scripts/demo-up.sh",
            "scripts/demo-down.sh",
            "scripts/demo-smoke.sh",
            "scripts/demo-up.ps1",
            "scripts/demo-down.ps1",
            "scripts/demo-smoke.ps1",
        ]

        missing_files = [file_name for file_name in expected_files if not (ROOT / file_name).exists()]

        self.assertEqual(missing_files, [])

    def test_demo_compose_includes_core_stack(self) -> None:
        compose = (ROOT / "infra/demo/docker-compose.yml").read_text(encoding="utf-8")
        expected_services = [
            "postgres:",
            "redis:",
            "kafka:",
            "kafka-init:",
            "kafka-ui:",
            "gateway:",
            "catalog-service:",
            "inventory-service:",
            "order-service:",
            "payment-service:",
            "promotion-service:",
            "fulfillment-service:",
            "read-model-service:",
            "customer-app:",
            "admin-app:",
        ]

        for service_name in expected_services:
            with self.subTest(service=service_name):
                self.assertIn(service_name, compose)

        expected_env = [
            "POSTGRES_HOST: postgres",
            "POSTGRES_PORT: 5432",
            "KAFKA_BOOTSTRAP_SERVERS: kafka:19092",
            "ORDER_SERVICE_URL: http://order-service:18083",
            "INVENTORY_SERVICE_URL: http://inventory-service:18082",
            "PAYMENT_SERVICE_URL: http://payment-service:18084",
            "PROMOTION_SERVICE_URL: http://promotion-service:18085",
            "READ_MODEL_SERVICE_URL: http://read-model-service:18087",
            "STOCKRUSH_KAFKA_LISTENERS_ENABLED: \"true\"",
        ]

        for env_line in expected_env:
            with self.subTest(env=env_line):
                self.assertIn(env_line, compose)

    def test_demo_scripts_reference_compose_file_and_env_template(self) -> None:
        for script_name in ["demo-up", "demo-down", "demo-smoke"]:
            shell_script = (ROOT / "scripts" / f"{script_name}.sh").read_text(encoding="utf-8")
            powershell_script = (ROOT / "scripts" / f"{script_name}.ps1").read_text(encoding="utf-8")

            with self.subTest(script=shell_script):
                self.assertIn("infra/demo/docker-compose.yml", shell_script)
                self.assertIn("infra/demo/.env.example", shell_script)

            with self.subTest(script=powershell_script):
                self.assertIn("infra/demo/docker-compose.yml", powershell_script)
                self.assertIn("infra/demo/.env.example", powershell_script)


if __name__ == "__main__":
    unittest.main()
