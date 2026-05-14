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

    def test_demo_uses_separate_host_ports_and_preflight(self) -> None:
        env_template = (ROOT / "infra/demo/.env.example").read_text(encoding="utf-8")
        compose = (ROOT / "infra/demo/docker-compose.yml").read_text(encoding="utf-8")
        shell_up = (ROOT / "scripts/demo-up.sh").read_text(encoding="utf-8")
        powershell_up = (ROOT / "scripts/demo-up.ps1").read_text(encoding="utf-8")

        expected_ports = [
            "POSTGRES_HOST_PORT=25432",
            "REDIS_HOST_PORT=26379",
            "KAFKA_HOST_PORT=29092",
            "KAFKA_UI_PORT=29090",
            "GATEWAY_HOST_PORT=28080",
            "CATALOG_HOST_PORT=28081",
            "CUSTOMER_APP_HOST_PORT=15173",
            "ADMIN_APP_HOST_PORT=15174",
        ]

        self.assertIn("DEMO_ENV_REV=2026-05-14-demo-ports-v2", env_template)
        for port_line in expected_ports:
            with self.subTest(port=port_line):
                self.assertIn(port_line, env_template)

        self.assertIn("${POSTGRES_HOST_PORT:-25432}:5432", compose)
        self.assertIn("${GATEWAY_HOST_PORT:-28080}:18080", compose)
        self.assertIn("${CUSTOMER_APP_HOST_PORT:-15173}:8080", compose)
        self.assertIn("--refresh-env", shell_up)
        self.assertIn("--skip-port-check", shell_up)
        self.assertIn("check_host_ports", shell_up)
        self.assertIn("--refresh-env", powershell_up)
        self.assertIn("--skip-port-check", powershell_up)
        self.assertIn("Test-DemoPorts", powershell_up)

    def test_demo_smoke_runs_order_flow_runner(self) -> None:
        shell_script = (ROOT / "scripts/demo-smoke.sh").read_text(encoding="utf-8")
        powershell_script = (ROOT / "scripts/demo-smoke.ps1").read_text(encoding="utf-8")

        self.assertIn("tools/local-e2e/local-e2e", shell_script)
        self.assertIn("demo-order-flow", shell_script)
        self.assertIn("tools/local-e2e/local-e2e", powershell_script)
        self.assertIn("demo-order-flow", powershell_script)


if __name__ == "__main__":
    unittest.main()
