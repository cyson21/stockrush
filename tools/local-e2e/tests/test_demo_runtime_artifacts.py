from __future__ import annotations

import unittest
from pathlib import Path

# 데모 기동 전에 필요한 인프라/스크립트 산출물이 존재하는지 보장하는 스모크 테스트입니다.


ROOT = Path(__file__).resolve().parents[3]


class DemoRuntimeArtifactsTest(unittest.TestCase):
    def test_demo_runtime_files_are_present(self) -> None:
        expected_files = [
            "infra/demo/.env.example",
            "infra/demo/docker-compose.yml",
            "infra/demo/keycloak/stockrush-realm.json",
            "infra/demo/docker-compose.images.yml",
            "infra/demo/README.md",
            "services/Dockerfile",
            "apps/Dockerfile",
            "apps/nginx/default.conf",
            "scripts/demo-up.sh",
            "scripts/demo-down.sh",
            "scripts/demo-smoke.sh",
            "scripts/deploy-local.sh",
            "scripts/with-java17.sh",
            "scripts/demo-up.ps1",
            "scripts/demo-down.ps1",
            "scripts/demo-smoke.ps1",
            "scripts/deploy-local.ps1",
            "scripts/with-java17.ps1",
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
            "keycloak:",
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
            "FULFILLMENT_SERVICE_URL: http://fulfillment-service:18086",
            "READ_MODEL_SERVICE_URL: http://read-model-service:18087",
            "STOCKRUSH_SECURITY_ISSUER_URI: http://localhost:${KEYCLOAK_HOST_PORT:-28088}/realms/stockrush",
            "STOCKRUSH_SECURITY_JWK_SET_URI: http://keycloak:8080/realms/stockrush/protocol/openid-connect/certs",
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
            "KEYCLOAK_HOST_PORT=28088",
            "GATEWAY_HOST_PORT=28080",
            "CATALOG_HOST_PORT=28081",
            "INVENTORY_HOST_PORT=28082",
            "ORDER_HOST_PORT=28083",
            "PAYMENT_HOST_PORT=28084",
            "PROMOTION_HOST_PORT=28085",
            "FULFILLMENT_HOST_PORT=28086",
            "READ_MODEL_HOST_PORT=28087",
            "CUSTOMER_APP_HOST_PORT=15173",
            "ADMIN_APP_HOST_PORT=15174",
        ]

        self.assertIn("DEMO_ENV_REV=2026-05-15-security-v1", env_template)
        for port_line in expected_ports:
            with self.subTest(port=port_line):
                self.assertIn(port_line, env_template)

        expected_image_env = [
            "STOCKRUSH_IMAGE_REGISTRY=ghcr.io",
            "STOCKRUSH_IMAGE_OWNER=cyson21",
            "STOCKRUSH_IMAGE_TAG=latest-demo",
        ]
        for env_line in expected_image_env:
            with self.subTest(env=env_line):
                self.assertIn(env_line, env_template)

        self.assertIn("${POSTGRES_HOST_PORT:-25432}:5432", compose)
        self.assertIn("${KEYCLOAK_HOST_PORT:-28088}:8080", compose)
        self.assertIn("KC_HOSTNAME: http://localhost:${KEYCLOAK_HOST_PORT:-28088}", compose)
        self.assertIn("${GATEWAY_HOST_PORT:-28080}:18080", compose)
        self.assertIn("${CUSTOMER_APP_HOST_PORT:-15173}:8080", compose)
        self.assertIn("http://127.0.0.1:8080", compose)
        self.assertIn("--refresh-env", shell_up)
        self.assertIn("--skip-port-check", shell_up)
        self.assertIn("check_host_ports", shell_up)
        self.assertIn("--refresh-env", powershell_up)
        self.assertIn("--skip-port-check", powershell_up)
        self.assertIn("Test-DemoPorts", powershell_up)
        self.assertIn('EXPECTED_ENV_REV="2026-05-15-security-v1"', shell_up)
        self.assertIn('$ExpectedEnvRev = "2026-05-15-security-v1"', powershell_up)

    def test_demo_image_override_targets_ghcr_images(self) -> None:
        image_compose = (ROOT / "infra/demo/docker-compose.images.yml").read_text(encoding="utf-8")
        shell_deploy = (ROOT / "scripts/deploy-local.sh").read_text(encoding="utf-8")
        powershell_deploy = (ROOT / "scripts/deploy-local.ps1").read_text(encoding="utf-8")
        release_images_workflow = (ROOT / ".github/workflows/release-images.yml").read_text(encoding="utf-8")

        expected_images = [
            "stockrush-catalog-service",
            "stockrush-inventory-service",
            "stockrush-order-service",
            "stockrush-payment-service",
            "stockrush-promotion-service",
            "stockrush-fulfillment-service",
            "stockrush-read-model-service",
            "stockrush-gateway",
            "stockrush-customer-app",
            "stockrush-admin-app",
        ]
        for image_name in expected_images:
            with self.subTest(image=image_name):
                self.assertIn(image_name, image_compose)

        self.assertIn("STOCKRUSH_IMAGE_REGISTRY", image_compose)
        self.assertIn("STOCKRUSH_IMAGE_OWNER", image_compose)
        self.assertIn("STOCKRUSH_IMAGE_TAG", image_compose)
        self.assertIn("docker login", shell_deploy)
        self.assertIn("docker compose", shell_deploy)
        self.assertIn("demo-smoke.sh", shell_deploy)
        self.assertIn("read:packages", shell_deploy)
        self.assertIn("package visibility", shell_deploy)
        self.assertIn("docker login", powershell_deploy)
        self.assertIn("docker compose", powershell_deploy)
        self.assertIn("demo-smoke.ps1", powershell_deploy)
        self.assertIn("read:packages", powershell_deploy)
        self.assertIn("package visibility", powershell_deploy)
        self.assertIn("docker/setup-qemu-action@v3", release_images_workflow)
        self.assertIn("platforms: linux/amd64,linux/arm64", release_images_workflow)

    def test_demo_smoke_runs_order_flow_and_burst_runners(self) -> None:
        shell_script = (ROOT / "scripts/demo-smoke.sh").read_text(encoding="utf-8")
        powershell_script = (ROOT / "scripts/demo-smoke.ps1").read_text(encoding="utf-8")

        self.assertIn("tools/local-e2e/local-e2e", shell_script)
        self.assertIn("demo-order-flow", shell_script)
        self.assertIn("burst-idempotency", shell_script)
        self.assertIn("--skip-burst", shell_script)
        self.assertIn("--kafka-outage", shell_script)
        self.assertIn("kafka-outage-recovery", shell_script)
        self.assertIn("pause kafka", shell_script)
        self.assertIn("unpause kafka", shell_script)
        self.assertIn("--promotion-url", shell_script)
        self.assertIn("get_keycloak_token", shell_script)
        self.assertIn("wait_for_keycloak_ready", shell_script)
        self.assertIn("--admin-bearer-token", shell_script)
        self.assertIn("--customer-bearer-token", shell_script)
        self.assertIn("--relay-mode automatic", shell_script)
        self.assertIn("--orders 12", shell_script)
        self.assertIn("--initial-stock 4", shell_script)
        self.assertIn("--quantity 1", shell_script)
        self.assertIn("--idempotency-replays 3", shell_script)
        self.assertIn("--relay-workers 4", shell_script)
        self.assertIn("--stability-waves 2", shell_script)
        self.assertIn("--max-attempts 30", shell_script)
        self.assertIn("--wait-seconds 1", shell_script)
        self.assertIn("PROMOTION_HOST_PORT", shell_script)
        self.assertIn("/actuator/info", shell_script)
        self.assertIn("/actuator/metrics", shell_script)
        self.assertIn("tools/local-e2e/local-e2e", powershell_script)
        self.assertIn("demo-order-flow", powershell_script)
        self.assertIn("burst-idempotency", powershell_script)
        self.assertIn("--skip-burst", powershell_script)
        self.assertIn("--kafka-outage", powershell_script)
        self.assertIn("kafka-outage-recovery", powershell_script)
        self.assertIn("pause kafka", powershell_script)
        self.assertIn("unpause kafka", powershell_script)
        self.assertIn("--promotion-url", powershell_script)
        self.assertIn("Get-KeycloakToken", powershell_script)
        self.assertIn("Wait-KeycloakReady", powershell_script)
        self.assertIn("Invoke-LocalE2E", powershell_script)
        self.assertIn("Get-Command python", powershell_script)
        self.assertIn("Get-Command py", powershell_script)
        self.assertIn("--admin-bearer-token", powershell_script)
        self.assertIn("--customer-bearer-token", powershell_script)
        self.assertIn("--relay-mode automatic", powershell_script)
        self.assertIn("--orders 12", powershell_script)
        self.assertIn("--initial-stock 4", powershell_script)
        self.assertIn("--quantity 1", powershell_script)
        self.assertIn("--idempotency-replays 3", powershell_script)
        self.assertIn("--relay-workers 4", powershell_script)
        self.assertIn("--stability-waves 2", powershell_script)
        self.assertIn("--max-attempts 30", powershell_script)
        self.assertIn("--wait-seconds 1", powershell_script)
        self.assertIn("$PromotionPort", powershell_script)
        self.assertIn("/actuator/info", powershell_script)
        self.assertIn("/actuator/metrics", powershell_script)

    def test_keycloak_realm_is_demo_scoped_and_port_override_friendly(self) -> None:
        realm = (ROOT / "infra/demo/keycloak/stockrush-realm.json").read_text(encoding="utf-8")

        self.assertIn('"clientId": "stockrush-demo-smoke"', realm)
        self.assertIn('"directAccessGrantsEnabled": true', realm)
        self.assertIn('"username": "customer.demo@stockrush.local"', realm)
        self.assertIn('"username": "admin.demo@stockrush.local"', realm)
        self.assertIn('"http://localhost:*/*"', realm)
        self.assertIn('"webOrigins": ["+"]', realm)


    def test_java17_wrappers_are_cross_platform(self) -> None:
        shell_script = (ROOT / "scripts/with-java17.sh").read_text(encoding="utf-8")
        powershell_script = (ROOT / "scripts/with-java17.ps1").read_text(encoding="utf-8")
        services_readme = (ROOT / "services/README.md").read_text(encoding="utf-8")

        for expected in [
            "STOCKRUSH_JAVA17_HOME",
            "JAVA_HOME",
            "java_home -v 17",
            "exec \"$@\"",
        ]:
            with self.subTest(shell=expected):
                self.assertIn(expected, shell_script)

        for expected in [
            "STOCKRUSH_JAVA17_HOME",
            "JAVA_HOME",
            "jdk-17",
            "& $Executable @Arguments",
        ]:
            with self.subTest(powershell=expected):
                self.assertIn(expected, powershell_script)

        self.assertIn("./scripts/with-java17.sh mvn -version", services_readme)
        self.assertIn(".\\scripts\\with-java17.ps1 mvn -version", services_readme)


if __name__ == "__main__":
    unittest.main()
