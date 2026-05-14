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

    def test_detects_service_missing_actuator_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            service_root = root / "services" / "order-service"
            service_root.mkdir(parents=True)
            (service_root / "pom.xml").write_text(
                "<project><dependencies></dependencies></project>\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertTrue(any(violation.rule_id == "ARCH-009" for violation in violations))

    def test_detects_service_missing_actuator_metrics_exposure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            service_root = root / "services" / "order-service"
            resource_root = service_root / "src" / "main" / "resources"
            resource_root.mkdir(parents=True)
            (service_root / "pom.xml").write_text(
                "<project><dependencies>"
                "<dependency><artifactId>spring-boot-starter-actuator</artifactId></dependency>"
                "</dependencies></project>\n",
                encoding="utf-8",
            )
            (resource_root / "application.yml").write_text(
                "management:\n"
                "  endpoints:\n"
                "    web:\n"
                "      exposure:\n"
                "        include: health,info\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertTrue(any(violation.rule_id == "ARCH-009" for violation in violations))

    def test_allows_service_with_actuator_health_info_metrics_exposure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            service_root = root / "services" / "order-service"
            resource_root = service_root / "src" / "main" / "resources"
            resource_root.mkdir(parents=True)
            (service_root / "pom.xml").write_text(
                "<project><dependencies>"
                "<dependency><artifactId>spring-boot-starter-actuator</artifactId></dependency>"
                "</dependencies></project>\n",
                encoding="utf-8",
            )
            (resource_root / "application.yml").write_text(
                "management:\n"
                "  endpoints:\n"
                "    web:\n"
                "      exposure:\n"
                "        include: health,info,metrics\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertFalse(any(violation.rule_id == "ARCH-009" for violation in violations))

    def test_allows_service_with_actuator_yaml_list_exposure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            service_root = root / "services" / "order-service"
            resource_root = service_root / "src" / "main" / "resources"
            resource_root.mkdir(parents=True)
            (service_root / "pom.xml").write_text(
                "<project><dependencies>"
                "<dependency><artifactId>spring-boot-starter-actuator</artifactId></dependency>"
                "</dependencies></project>\n",
                encoding="utf-8",
            )
            (resource_root / "application.yml").write_text(
                "management:\n"
                "  endpoints:\n"
                "    web:\n"
                "      exposure:\n"
                "        include:\n"
                "          - health\n"
                "          - info\n"
                "          - metrics\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertFalse(any(violation.rule_id == "ARCH-009" for violation in violations))

    def test_detects_gateway_missing_correlation_id_filter(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            service_root = root / "services" / "gateway"
            java_root = service_root / "src" / "main" / "java" / "com" / "stockrush" / "gateway"
            resource_root = service_root / "src" / "main" / "resources"
            java_root.mkdir(parents=True)
            resource_root.mkdir(parents=True)
            (service_root / "pom.xml").write_text(
                "<project><dependencies>"
                "<dependency><artifactId>spring-boot-starter-actuator</artifactId></dependency>"
                "</dependencies></project>\n",
                encoding="utf-8",
            )
            (resource_root / "application.yml").write_text(
                "management:\n"
                "  endpoints:\n"
                "    web:\n"
                "      exposure:\n"
                "        include: health,info,metrics\n",
                encoding="utf-8",
            )
            (java_root / "GatewayApplication.java").write_text(
                "@SpringBootApplication\npublic class GatewayApplication {}\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertTrue(any(violation.rule_id == "ARCH-007" for violation in violations))

    def test_allows_gateway_correlation_id_filter_with_mdc(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            service_root = root / "services" / "gateway"
            java_root = service_root / "src" / "main" / "java" / "com" / "stockrush" / "gateway"
            resource_root = service_root / "src" / "main" / "resources"
            java_root.mkdir(parents=True)
            resource_root.mkdir(parents=True)
            (service_root / "pom.xml").write_text(
                "<project><dependencies>"
                "<dependency><artifactId>spring-boot-starter-actuator</artifactId></dependency>"
                "</dependencies></project>\n",
                encoding="utf-8",
            )
            (resource_root / "application.yml").write_text(
                "management:\n"
                "  endpoints:\n"
                "    web:\n"
                "      exposure:\n"
                "        include: health,info,metrics\n",
                encoding="utf-8",
            )
            (java_root / "CorrelationIdFilter.java").write_text(
                "import jakarta.servlet.http.HttpServletRequestWrapper;\n"
                "import org.slf4j.MDC;\n"
                "import org.springframework.web.filter.OncePerRequestFilter;\n"
                "public class CorrelationIdFilter extends OncePerRequestFilter {\n"
                "  static final String HEADER = \"X-Correlation-Id\";\n"
                "  protected void doFilterInternal(request, response, filterChain) {\n"
                "    response.setHeader(HEADER, \"corr\");\n"
                "    MDC.put(\"correlationId\", \"corr\");\n"
                "    try { filterChain.doFilter(new CorrelationHeaderRequest(request), response); }\n"
                "    finally { MDC.remove(\"correlationId\"); }\n"
                "  }\n"
                "  static class CorrelationHeaderRequest extends HttpServletRequestWrapper {\n"
                "    String getHeader(String name) { return \"corr\"; }\n"
                "    java.util.Enumeration<String> getHeaders(String name) { return null; }\n"
                "    java.util.Enumeration<String> getHeaderNames() { return null; }\n"
                "  }\n"
                "}\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertFalse(any(violation.rule_id == "ARCH-007" for violation in violations))

    def test_detects_gateway_correlation_filter_without_request_wrapping_behavior(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            service_root = root / "services" / "gateway"
            java_root = service_root / "src" / "main" / "java" / "com" / "stockrush" / "gateway"
            resource_root = service_root / "src" / "main" / "resources"
            java_root.mkdir(parents=True)
            resource_root.mkdir(parents=True)
            (service_root / "pom.xml").write_text(
                "<project><dependencies>"
                "<dependency><artifactId>spring-boot-starter-actuator</artifactId></dependency>"
                "</dependencies></project>\n",
                encoding="utf-8",
            )
            (resource_root / "application.yml").write_text(
                "management:\n"
                "  endpoints:\n"
                "    web:\n"
                "      exposure:\n"
                "        include: health,info,metrics\n",
                encoding="utf-8",
            )
            (java_root / "CorrelationIdFilter.java").write_text(
                "import jakarta.servlet.http.HttpServletRequestWrapper;\n"
                "import org.slf4j.MDC;\n"
                "import org.springframework.web.filter.OncePerRequestFilter;\n"
                "public class CorrelationIdFilter extends OncePerRequestFilter {\n"
                "  static final String HEADER = \"X-Correlation-Id\";\n"
                "  void apply(String correlationId) { MDC.put(\"correlationId\", correlationId); MDC.remove(\"correlationId\"); }\n"
                "}\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertTrue(any(violation.rule_id == "ARCH-007" for violation in violations))

    def test_detects_api_service_missing_correlation_id_filter(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            java_root = root / "services" / "order-service" / "src" / "main" / "java" / "com" / "stockrush" / "order" / "api"
            java_root.mkdir(parents=True)
            (java_root / "CorrelationIds.java").write_text(
                "package com.stockrush.order.api;\n"
                "final class CorrelationIds {\n"
                "  static final String HEADER_NAME = \"X-Correlation-Id\";\n"
                "  static String resolve(String value) { return value; }\n"
                "}\n",
                encoding="utf-8",
            )
            (java_root / "OrderController.java").write_text(
                "package com.stockrush.order.api;\n"
                "@RestController class OrderController {}\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertTrue(any(violation.rule_id == "ARCH-010" for violation in violations))

    def test_detects_fulfillment_api_service_missing_correlation_id_filter(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            java_root = (
                root
                / "services"
                / "fulfillment-service"
                / "src"
                / "main"
                / "java"
                / "com"
                / "stockrush"
                / "fulfillment"
                / "api"
            )
            java_root.mkdir(parents=True)
            (java_root / "FulfillmentAdminController.java").write_text(
                "package com.stockrush.fulfillment.api;\n"
                "@RestController class FulfillmentAdminController {}\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertTrue(any(violation.rule_id == "ARCH-010" for violation in violations))

    def test_allows_api_service_correlation_id_filter_with_mdc_and_request_wrapping(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            java_root = root / "services" / "order-service" / "src" / "main" / "java" / "com" / "stockrush" / "order" / "api"
            java_root.mkdir(parents=True)
            (java_root / "CorrelationIds.java").write_text(
                "package com.stockrush.order.api;\n"
                "final class CorrelationIds {\n"
                "  static final String HEADER_NAME = \"X-Correlation-Id\";\n"
                "  static String resolve(String value) { return value; }\n"
                "}\n",
                encoding="utf-8",
            )
            (java_root / "OrderController.java").write_text(
                "package com.stockrush.order.api;\n"
                "@RestController class OrderController {}\n",
                encoding="utf-8",
            )
            (java_root / "CorrelationIdFilter.java").write_text(
                "package com.stockrush.order.api;\n"
                "import jakarta.servlet.http.HttpServletRequestWrapper;\n"
                "import org.slf4j.MDC;\n"
                "import org.springframework.core.Ordered;\n"
                "import org.springframework.core.annotation.Order;\n"
                "import org.springframework.stereotype.Component;\n"
                "import org.springframework.web.filter.OncePerRequestFilter;\n"
                "@Component\n"
                "@Order(Ordered.HIGHEST_PRECEDENCE)\n"
                "class CorrelationIdFilter extends OncePerRequestFilter {\n"
                "  protected void doFilterInternal(request, response, filterChain) {\n"
                "    String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));\n"
                "    response.setHeader(CorrelationIds.HEADER_NAME, correlationId);\n"
                "    MDC.put(\"correlationId\", correlationId);\n"
                "    try { filterChain.doFilter(new CorrelationHeaderRequest(request, correlationId), response); }\n"
                "    finally { MDC.remove(\"correlationId\"); }\n"
                "  }\n"
                "  static class CorrelationHeaderRequest extends HttpServletRequestWrapper {\n"
                "    String getHeader(String name) { return \"corr\"; }\n"
                "    java.util.Enumeration<String> getHeaders(String name) { return null; }\n"
                "    java.util.Enumeration<String> getHeaderNames() { return null; }\n"
                "  }\n"
                "}\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertFalse(any(violation.rule_id == "ARCH-010" for violation in violations))

    def test_detects_api_service_correlation_filter_only_in_test_sources(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            java_root = root / "services" / "order-service" / "src" / "main" / "java" / "com" / "stockrush" / "order" / "api"
            test_root = root / "services" / "order-service" / "src" / "test" / "java" / "com" / "stockrush" / "order" / "api"
            java_root.mkdir(parents=True)
            test_root.mkdir(parents=True)
            (java_root / "CorrelationIds.java").write_text(
                "package com.stockrush.order.api;\n"
                "final class CorrelationIds {\n"
                "  static final String HEADER_NAME = \"X-Correlation-Id\";\n"
                "  static String resolve(String value) { return value; }\n"
                "}\n",
                encoding="utf-8",
            )
            (java_root / "OrderController.java").write_text(
                "package com.stockrush.order.api;\n"
                "@RestController class OrderController {}\n",
                encoding="utf-8",
            )
            (test_root / "CorrelationIdFilter.java").write_text(
                "package com.stockrush.order.api;\n"
                "import jakarta.servlet.http.HttpServletRequestWrapper;\n"
                "import org.slf4j.MDC;\n"
                "import org.springframework.core.Ordered;\n"
                "import org.springframework.core.annotation.Order;\n"
                "import org.springframework.stereotype.Component;\n"
                "import org.springframework.web.filter.OncePerRequestFilter;\n"
                "@Component\n"
                "@Order(Ordered.HIGHEST_PRECEDENCE)\n"
                "class CorrelationIdFilter extends OncePerRequestFilter {\n"
                "  protected void doFilterInternal(request, response, filterChain) {\n"
                "    String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));\n"
                "    response.setHeader(CorrelationIds.HEADER_NAME, correlationId);\n"
                "    MDC.put(\"correlationId\", correlationId);\n"
                "    try { filterChain.doFilter(new CorrelationHeaderRequest(request, correlationId), response); }\n"
                "    finally { MDC.remove(\"correlationId\"); }\n"
                "  }\n"
                "  static class CorrelationHeaderRequest extends HttpServletRequestWrapper {\n"
                "    String getHeader(String name) { return \"corr\"; }\n"
                "    java.util.Enumeration<String> getHeaders(String name) { return null; }\n"
                "    java.util.Enumeration<String> getHeaderNames() { return null; }\n"
                "  }\n"
                "}\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertTrue(any(violation.rule_id == "ARCH-010" for violation in violations))

    def test_detects_api_service_correlation_filter_without_component_registration(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            java_root = root / "services" / "order-service" / "src" / "main" / "java" / "com" / "stockrush" / "order" / "api"
            java_root.mkdir(parents=True)
            (java_root / "CorrelationIds.java").write_text(
                "package com.stockrush.order.api;\n"
                "final class CorrelationIds {\n"
                "  static final String HEADER_NAME = \"X-Correlation-Id\";\n"
                "  static String resolve(String value) { return value; }\n"
                "}\n",
                encoding="utf-8",
            )
            (java_root / "OrderController.java").write_text(
                "package com.stockrush.order.api;\n"
                "@RestController class OrderController {}\n",
                encoding="utf-8",
            )
            (java_root / "CorrelationIdFilter.java").write_text(
                "package com.stockrush.order.api;\n"
                "import jakarta.servlet.http.HttpServletRequestWrapper;\n"
                "import org.slf4j.MDC;\n"
                "import org.springframework.web.filter.OncePerRequestFilter;\n"
                "class CorrelationIdFilter extends OncePerRequestFilter {\n"
                "  protected void doFilterInternal(request, response, filterChain) {\n"
                "    String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));\n"
                "    response.setHeader(CorrelationIds.HEADER_NAME, correlationId);\n"
                "    MDC.put(\"correlationId\", correlationId);\n"
                "    try { filterChain.doFilter(new CorrelationHeaderRequest(request, correlationId), response); }\n"
                "    finally { MDC.remove(\"correlationId\"); }\n"
                "  }\n"
                "  static class CorrelationHeaderRequest extends HttpServletRequestWrapper {\n"
                "    String getHeader(String name) { return \"corr\"; }\n"
                "    java.util.Enumeration<String> getHeaders(String name) { return null; }\n"
                "    java.util.Enumeration<String> getHeaderNames() { return null; }\n"
                "  }\n"
                "}\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertTrue(any(violation.rule_id == "ARCH-010" for violation in violations))

    def test_detects_api_service_correlation_filter_without_mdc_cleanup(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            java_root = root / "services" / "order-service" / "src" / "main" / "java" / "com" / "stockrush" / "order" / "api"
            java_root.mkdir(parents=True)
            (java_root / "CorrelationIds.java").write_text(
                "package com.stockrush.order.api;\n"
                "final class CorrelationIds {\n"
                "  static final String HEADER_NAME = \"X-Correlation-Id\";\n"
                "  static String resolve(String value) { return value; }\n"
                "}\n",
                encoding="utf-8",
            )
            (java_root / "OrderController.java").write_text(
                "package com.stockrush.order.api;\n"
                "@RestController class OrderController {}\n",
                encoding="utf-8",
            )
            (java_root / "CorrelationIdFilter.java").write_text(
                "package com.stockrush.order.api;\n"
                "import jakarta.servlet.http.HttpServletRequestWrapper;\n"
                "import org.slf4j.MDC;\n"
                "import org.springframework.web.filter.OncePerRequestFilter;\n"
                "class CorrelationIdFilter extends OncePerRequestFilter {\n"
                "  protected void doFilterInternal(request, response, filterChain) {\n"
                "    String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));\n"
                "    response.setHeader(CorrelationIds.HEADER_NAME, correlationId);\n"
                "    MDC.put(\"correlationId\", correlationId);\n"
                "    filterChain.doFilter(new CorrelationHeaderRequest(request, correlationId), response);\n"
                "  }\n"
                "  static class CorrelationHeaderRequest extends HttpServletRequestWrapper {\n"
                "    String getHeader(String name) { return \"corr\"; }\n"
                "    java.util.Enumeration<String> getHeaders(String name) { return null; }\n"
                "    java.util.Enumeration<String> getHeaderNames() { return null; }\n"
                "  }\n"
                "}\n",
                encoding="utf-8",
            )

            violations = architecture_guard.check(root)

            self.assertTrue(any(violation.rule_id == "ARCH-010" for violation in violations))

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
