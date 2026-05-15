package com.stockrush.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderGatewayControllerIntegrationTest {

    private static final StubService STUB_CATALOG_SERVICE = new StubService(
        "catalog",
        "ProductChanged",
        "stockrush.catalog.events.v1"
    );
    private static final StubService STUB_ORDER_SERVICE = new StubService(
        "order",
        "OrderCreated",
        "stockrush.order.events.v1"
    );
    private static final StubService STUB_INVENTORY_SERVICE = new StubService(
        "inventory",
        "InventoryReserved",
        "stockrush.inventory.events.v1"
    );
    private static final StubService STUB_PAYMENT_SERVICE = new StubService(
        "payment",
        "PaymentAuthorized",
        "stockrush.payment.events.v1"
    );
    private static final StubService STUB_PROMOTION_SERVICE = new StubService(
        "promotion",
        "CouponUsageReserved",
        "stockrush.promotion.events.v1"
    );
    private static final StubService STUB_FULFILLMENT_SERVICE = new StubService(
        "fulfillment",
        "FulfillmentRequestPrepared",
        "stockrush.fulfillment.events.v1"
    );
    private static final StubService STUB_READ_MODEL_SERVICE = new StubService(
        "read-model",
        "OrderSummaryProjected",
        "stockrush.read-model.events.v1"
    );

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        STUB_CATALOG_SERVICE.start();
        STUB_ORDER_SERVICE.start();
        STUB_INVENTORY_SERVICE.start();
        STUB_PAYMENT_SERVICE.start();
        STUB_PROMOTION_SERVICE.start();
        STUB_FULFILLMENT_SERVICE.start();
        STUB_READ_MODEL_SERVICE.start();
        registry.add("stockrush.routes.catalog-service-url", STUB_CATALOG_SERVICE::baseUrl);
        registry.add("stockrush.routes.order-service-url", STUB_ORDER_SERVICE::baseUrl);
        registry.add("stockrush.routes.inventory-service-url", STUB_INVENTORY_SERVICE::baseUrl);
        registry.add("stockrush.routes.payment-service-url", STUB_PAYMENT_SERVICE::baseUrl);
        registry.add("stockrush.routes.promotion-service-url", STUB_PROMOTION_SERVICE::baseUrl);
        registry.add("stockrush.routes.fulfillment-service-url", STUB_FULFILLMENT_SERVICE::baseUrl);
        registry.add("stockrush.routes.read-model-service-url", STUB_READ_MODEL_SERVICE::baseUrl);
    }

    @BeforeEach
    void setUp() {
        STUB_CATALOG_SERVICE.reset();
        STUB_ORDER_SERVICE.reset();
        STUB_INVENTORY_SERVICE.reset();
        STUB_PAYMENT_SERVICE.reset();
        STUB_PROMOTION_SERVICE.reset();
        STUB_FULFILLMENT_SERVICE.reset();
        STUB_READ_MODEL_SERVICE.reset();
    }

    @AfterAll
    static void tearDown() {
        STUB_CATALOG_SERVICE.stop();
        STUB_ORDER_SERVICE.stop();
        STUB_INVENTORY_SERVICE.stop();
        STUB_PAYMENT_SERVICE.stop();
        STUB_PROMOTION_SERVICE.stop();
        STUB_FULFILLMENT_SERVICE.stop();
        STUB_READ_MODEL_SERVICE.stop();
    }

    @Test
    void rejects_customer_order_routes_without_bearer_token() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(gatewayUri("/api/orders/ord_gateway_001"))
            .header("X-Correlation-Id", "corr-gateway-customer-unauthenticated")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_READ_MODEL_SERVICE.assertNoRequests();
    }

    @Test
    void forwards_authenticated_customer_subject_and_ignores_spoofed_subject_header() throws Exception {
        String requestBody = """
            {
              "memberId": "spoofed-body-member",
              "paymentMethod": "CARD",
              "items": [
                {
                  "productCode": "LIMITED-GW",
                  "skuId": "LIMITED-GW-S",
                  "quantity": 1,
                  "unitPrice": 12000
                }
              ]
            }
            """;

        HttpRequest request = customerRequest("/api/orders")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "idem-gateway-customer-create")
            .header("X-Correlation-Id", "corr-gateway-customer-create")
            .header("X-StockRush-Subject", "spoofed-header-member")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(201);
        RecordedRequest forwarded = STUB_ORDER_SERVICE.singleRequest();
        assertThat(forwarded.firstHeader("X-StockRush-Subject")).contains("customer-demo");
    }

    @Test
    void routes_create_order_to_order_service() throws Exception {
        String requestBody = """
            {
              "memberId": "member-gateway",
              "paymentMethod": "CARD",
              "items": [
                {
                  "productCode": "LIMITED-GW",
                  "skuId": "LIMITED-GW-S",
                  "quantity": 1,
                  "unitPrice": 12000
                }
              ]
            }
            """;

        HttpRequest request = customerRequest("/api/orders")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "idem-gateway-create")
            .header("X-Correlation-Id", "corr-gateway-create")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).contains("/api/orders/ord_gateway_001");
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-create");
        assertThat(response.body()).contains("\"orderId\":\"ord_gateway_001\"");

        RecordedRequest forwarded = STUB_ORDER_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("POST");
        assertThat(forwarded.path()).isEqualTo("/api/orders");
        assertThat(forwarded.firstHeader("Idempotency-Key")).contains("idem-gateway-create");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-create");
        assertThat(forwarded.body()).contains("\"memberId\": \"member-gateway\"");
    }

    @Test
    void creates_missing_correlation_id_for_command_routes() throws Exception {
        String requestBody = """
            {
              "memberId": "member-gateway-generated",
              "paymentMethod": "CARD",
              "items": [
                {
                  "productCode": "LIMITED-GW-GENERATED",
                  "skuId": "LIMITED-GW-GENERATED-S",
                  "quantity": 1,
                  "unitPrice": 12000
                }
              ]
            }
            """;

        HttpRequest request = customerRequest("/api/orders")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "idem-gateway-generated")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(201);
        String generatedCorrelationId = response.headers()
            .firstValue("X-Correlation-Id")
            .orElseThrow();
        assertThat(generatedCorrelationId).isNotBlank();
        assertThat(response.body()).contains("\"correlationId\":\"" + generatedCorrelationId + "\"");

        RecordedRequest forwarded = STUB_ORDER_SERVICE.singleRequest();
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains(generatedCorrelationId);
        assertThat(forwarded.firstHeader("Idempotency-Key")).contains("idem-gateway-generated");
        assertThat(forwarded.body()).contains("\"memberId\": \"member-gateway-generated\"");
    }

    @Test
    void routes_order_detail_query_to_order_service() throws Exception {
        HttpRequest request = customerRequest("/api/orders/ord_gateway_001")
            .header("X-Correlation-Id", "corr-gateway-query")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-query");
        assertThat(response.body()).contains("\"orderId\":\"ord_gateway_001\"");
        assertThat(response.body()).contains("\"status\":\"CONFIRMED\"");

        RecordedRequest forwarded = STUB_ORDER_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("GET");
        assertThat(forwarded.path()).isEqualTo("/api/orders/ord_gateway_001");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-query");
    }

    @Test
    void rejects_admin_routes_without_bearer_token() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(gatewayUri("/api/admin/orders?page=0&size=5"))
            .header("X-Correlation-Id", "corr-gateway-admin-unauthenticated")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_INVENTORY_SERVICE.assertNoRequests();
        STUB_PAYMENT_SERVICE.assertNoRequests();
        STUB_PROMOTION_SERVICE.assertNoRequests();
        STUB_FULFILLMENT_SERVICE.assertNoRequests();
        STUB_READ_MODEL_SERVICE.assertNoRequests();
    }

    @Test
    void rejects_admin_routes_with_customer_role() throws Exception {
        HttpRequest request = customerRequest("/api/admin/orders?page=0&size=5")
            .header("X-Correlation-Id", "corr-gateway-admin-forbidden")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_INVENTORY_SERVICE.assertNoRequests();
        STUB_PAYMENT_SERVICE.assertNoRequests();
        STUB_PROMOTION_SERVICE.assertNoRequests();
        STUB_FULFILLMENT_SERVICE.assertNoRequests();
        STUB_READ_MODEL_SERVICE.assertNoRequests();
    }

    @Test
    void routes_admin_order_list_query_to_order_service() throws Exception {
        HttpRequest request = adminRequest("/api/admin/orders?page=0&size=5&status=CREATED&sagaStatus=PAYMENT_DELAYED")
            .header("X-Correlation-Id", "corr-gateway-admin-list")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-admin-list");
        assertThat(response.body()).contains("\"orderId\":\"ord_gateway_delay\"");

        RecordedRequest forwarded = STUB_ORDER_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("GET");
        assertThat(forwarded.path()).isEqualTo("/api/admin/orders");
        assertThat(forwarded.query()).contains("page=0&size=5&status=CREATED&sagaStatus=PAYMENT_DELAYED");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-admin-list");
    }

    @Test
    void routes_admin_order_saga_query_to_order_service() throws Exception {
        HttpRequest request = adminRequest("/api/admin/orders/ord_gateway_delay/saga")
            .header("X-Correlation-Id", "corr-gateway-admin-saga")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-admin-saga");
        assertThat(response.body()).contains("\"sagaStatus\":\"PAYMENT_DELAYED\"");

        RecordedRequest forwarded = STUB_ORDER_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("GET");
        assertThat(forwarded.path()).isEqualTo("/api/admin/orders/ord_gateway_delay/saga");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-admin-saga");
    }

    @Test
    void rejects_admin_catalog_routes_without_bearer_token() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(gatewayUri("/api/admin/products?page=0&size=20"))
            .header("X-Correlation-Id", "corr-gateway-admin-products-unauthenticated")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        STUB_CATALOG_SERVICE.assertNoRequests();
        STUB_INVENTORY_SERVICE.assertNoRequests();
    }

    @Test
    void rejects_admin_stock_routes_with_customer_role() throws Exception {
        HttpRequest request = customerRequest("/api/admin/stocks?productCode=SK-0001&page=0&size=5")
            .header("X-Correlation-Id", "corr-gateway-admin-stocks-customer")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        STUB_INVENTORY_SERVICE.assertNoRequests();
    }

    @Test
    void routes_admin_catalog_products_list_query_to_catalog_service() throws Exception {
        HttpRequest request = adminRequest("/api/admin/products?status=ACTIVE&page=0&size=20")
            .header("X-Correlation-Id", "corr-gateway-admin-products")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-admin-products");
        assertThat(response.body()).contains("\"productCode\":\"PROD-001\"");

        RecordedRequest forwarded = STUB_CATALOG_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("GET");
        assertThat(forwarded.path()).isEqualTo("/api/products");
        assertThat(forwarded.query()).contains("status=ACTIVE&page=0&size=20");
        assertThat(forwarded.firstHeader("X-Operator-Id")).contains("admin-demo");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-admin-products");
        STUB_INVENTORY_SERVICE.assertNoRequests();
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_PROMOTION_SERVICE.assertNoRequests();
        STUB_FULFILLMENT_SERVICE.assertNoRequests();
        STUB_READ_MODEL_SERVICE.assertNoRequests();
    }

    @Test
    void routes_admin_catalog_product_create_command_to_catalog_service() throws Exception {
        String requestBody = """
            {
              "productCode": "PROD-007",
              "name": "Gateway Test Product",
              "salesStatus": "ON_SALE",
              "listPrice": 12300
            }
            """;

        HttpRequest request = adminRequest("/api/admin/products")
            .header("Content-Type", "application/json")
            .header("X-Correlation-Id", "corr-gateway-admin-products-create")
            .header("X-Operator-Id", "spoofed-operator")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-admin-products-create");
        assertThat(response.body()).contains("\"productCode\":\"PROD-007\"");

        RecordedRequest forwarded = STUB_CATALOG_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("POST");
        assertThat(forwarded.path()).isEqualTo("/api/admin/products");
        assertThat(forwarded.firstHeader("X-Operator-Id")).contains("admin-demo");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-admin-products-create");
        assertThat(forwarded.body()).contains("\"productCode\": \"PROD-007\"");
        STUB_INVENTORY_SERVICE.assertNoRequests();
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_PROMOTION_SERVICE.assertNoRequests();
        STUB_FULFILLMENT_SERVICE.assertNoRequests();
        STUB_READ_MODEL_SERVICE.assertNoRequests();
    }

    @Test
    void routes_admin_catalog_product_update_command_to_catalog_service() throws Exception {
        String requestBody = """
            {
              "name": "Gateway Test Product Updated",
              "salesStatus": "ON_SALE",
              "listPrice": 15900
            }
            """;

        HttpRequest request = adminRequest("/api/admin/products/PROD-007")
            .header("Content-Type", "application/json")
            .header("X-Correlation-Id", "corr-gateway-admin-products-update")
            .header("X-Operator-Id", "spoofed-operator")
            .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-admin-products-update");
        assertThat(response.body()).contains("\"productCode\":\"PROD-007\"");

        RecordedRequest forwarded = STUB_CATALOG_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("PUT");
        assertThat(forwarded.path()).isEqualTo("/api/admin/products/PROD-007");
        assertThat(forwarded.firstHeader("X-Operator-Id")).contains("admin-demo");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-admin-products-update");
        assertThat(forwarded.body()).contains("\"listPrice\": 15900");
        STUB_INVENTORY_SERVICE.assertNoRequests();
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_PROMOTION_SERVICE.assertNoRequests();
        STUB_FULFILLMENT_SERVICE.assertNoRequests();
        STUB_READ_MODEL_SERVICE.assertNoRequests();
    }

    @Test
    void routes_admin_stock_list_query_to_inventory_service() throws Exception {
        HttpRequest request = adminRequest("/api/admin/stocks?productCode=PROD-001&page=0&size=25")
            .header("X-Correlation-Id", "corr-gateway-admin-stocks")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-admin-stocks");
        assertThat(response.body()).contains("\"skuId\":\"SKU-007\"");

        RecordedRequest forwarded = STUB_INVENTORY_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("GET");
        assertThat(forwarded.path()).isEqualTo("/api/stocks");
        assertThat(forwarded.query()).contains("productCode=PROD-001&page=0&size=25");
        assertThat(forwarded.firstHeader("X-Operator-Id")).contains("admin-demo");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-admin-stocks");
        STUB_CATALOG_SERVICE.assertNoRequests();
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_PROMOTION_SERVICE.assertNoRequests();
        STUB_FULFILLMENT_SERVICE.assertNoRequests();
        STUB_READ_MODEL_SERVICE.assertNoRequests();
    }

    @Test
    void routes_admin_stock_set_command_to_inventory_service() throws Exception {
        String requestBody = """
            {
              "productCode": "PROD-001",
              "availableQuantity": 20
            }
            """;

        HttpRequest request = adminRequest("/api/admin/stocks/SKU-007")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "idem-gateway-admin-stock-set")
            .header("X-Correlation-Id", "corr-gateway-admin-stock-set")
            .header("X-Operator-Id", "spoofed-operator")
            .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-admin-stock-set");
        assertThat(response.body()).contains("\"skuId\":\"SKU-007\"");
        assertThat(response.body()).contains("\"availableQuantity\":20");

        RecordedRequest forwarded = STUB_INVENTORY_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("PUT");
        assertThat(forwarded.path()).isEqualTo("/api/stocks/SKU-007");
        assertThat(forwarded.firstHeader("Idempotency-Key")).contains("idem-gateway-admin-stock-set");
        assertThat(forwarded.firstHeader("X-Operator-Id")).contains("admin-demo");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-admin-stock-set");
        assertThat(forwarded.body()).contains("\"availableQuantity\": 20");
        STUB_CATALOG_SERVICE.assertNoRequests();
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_PROMOTION_SERVICE.assertNoRequests();
        STUB_FULFILLMENT_SERVICE.assertNoRequests();
        STUB_READ_MODEL_SERVICE.assertNoRequests();
    }

    @Test
    void routes_admin_order_cancel_command_to_order_service() throws Exception {
        HttpRequest request = adminRequest("/api/admin/orders/ord_gateway_delay/cancel")
            .header("Idempotency-Key", "idem-gateway-admin-cancel")
            .header("X-Correlation-Id", "corr-gateway-admin-cancel")
            .header("X-Operator-Id", "spoofed-operator")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-admin-cancel");
        assertThat(response.body()).contains("\"sagaStatus\":\"PAYMENT_CANCEL_REQUESTED\"");

        RecordedRequest forwarded = STUB_ORDER_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("POST");
        assertThat(forwarded.path()).isEqualTo("/api/admin/orders/ord_gateway_delay/cancel");
        assertThat(forwarded.firstHeader("Idempotency-Key")).contains("idem-gateway-admin-cancel");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-admin-cancel");
        assertThat(forwarded.firstHeader("X-Operator-Id")).contains("admin-demo");
    }

    @Test
    void routes_order_outbox_list_query_to_order_service() throws Exception {
        HttpRequest request = adminRequest("/api/admin/outbox-services/order/events?status=PENDING,FAILED&limit=5&offset=0")
            .header("X-Correlation-Id", "corr-gateway-order-outbox")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-order-outbox");
        assertThat(response.body()).contains("\"service\":\"order\"");
        assertThat(response.body()).contains("\"eventType\":\"OrderCreated\"");

        RecordedRequest forwarded = STUB_ORDER_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("GET");
        assertThat(forwarded.path()).isEqualTo("/api/admin/outbox-events");
        assertThat(forwarded.query()).contains("status=PENDING,FAILED&limit=5&offset=0");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-order-outbox");
        STUB_INVENTORY_SERVICE.assertNoRequests();
        STUB_PAYMENT_SERVICE.assertNoRequests();
    }

    @Test
    void routes_inventory_outbox_retry_command_to_inventory_service() throws Exception {
        HttpRequest request = adminRequest("/api/admin/outbox-services/inventory/events/retry?batchSize=7")
            .header("X-Correlation-Id", "corr-gateway-inventory-retry")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-inventory-retry");
        assertThat(response.body()).contains("\"claimed\":7");
        assertThat(response.body()).contains("\"service\":\"inventory\"");

        RecordedRequest forwarded = STUB_INVENTORY_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("POST");
        assertThat(forwarded.path()).isEqualTo("/api/admin/outbox-events/retry");
        assertThat(forwarded.query()).contains("batchSize=7");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-inventory-retry");
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_PAYMENT_SERVICE.assertNoRequests();
    }

    @Test
    void routes_payment_outbox_failed_requeue_command_to_payment_service() throws Exception {
        HttpRequest request = adminRequest("/api/admin/outbox-services/payment/events/failed/requeue?batchSize=3")
            .header("X-Correlation-Id", "corr-gateway-payment-requeue")
            .header("X-Operator-Id", "spoofed-operator")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-payment-requeue");
        assertThat(response.body()).contains("\"updated\":3");
        assertThat(response.body()).contains("\"service\":\"payment\"");

        RecordedRequest forwarded = STUB_PAYMENT_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("POST");
        assertThat(forwarded.path()).isEqualTo("/api/admin/outbox-events/failed/requeue");
        assertThat(forwarded.query()).contains("batchSize=3");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-payment-requeue");
        assertThat(forwarded.firstHeader("X-Operator-Id")).contains("admin-demo");
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_INVENTORY_SERVICE.assertNoRequests();
    }

    @Test
    void routes_payment_outbox_list_query_to_payment_service() throws Exception {
        HttpRequest request = adminRequest("/api/admin/outbox-services/payment/events?status=FAILED")
            .header("X-Correlation-Id", "corr-gateway-payment-outbox")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-payment-outbox");
        assertThat(response.body()).contains("\"service\":\"payment\"");
        assertThat(response.body()).contains("\"eventType\":\"PaymentAuthorized\"");

        RecordedRequest forwarded = STUB_PAYMENT_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("GET");
        assertThat(forwarded.path()).isEqualTo("/api/admin/outbox-events");
        assertThat(forwarded.query()).contains("status=FAILED");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-payment-outbox");
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_INVENTORY_SERVICE.assertNoRequests();
    }

    @Test
    void routes_coupon_quote_command_to_promotion_service() throws Exception {
        String requestBody = """
            {
              "couponCode": "WELCOME10",
              "orderAmount": 80000.00
            }
            """;

        HttpRequest request = HttpRequest.newBuilder(gatewayUri("/api/coupons/quote"))
            .header("Content-Type", "application/json")
            .header("X-Correlation-Id", "corr-gateway-coupon-quote")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-coupon-quote");
        assertThat(response.body()).contains("\"couponCode\":\"WELCOME10\"");
        assertThat(response.body()).contains("\"discountAmount\":5000.0");

        RecordedRequest forwarded = STUB_PROMOTION_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("POST");
        assertThat(forwarded.path()).isEqualTo("/api/coupons/quote");
        assertThat(forwarded.firstHeader("Content-Type")).contains("application/json");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-coupon-quote");
        assertThat(forwarded.body()).contains("\"couponCode\": \"WELCOME10\"");
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_READ_MODEL_SERVICE.assertNoRequests();
    }

    @Test
    void routes_admin_coupon_usage_history_to_promotion_service() throws Exception {
        HttpRequest request = adminRequest("/api/admin/coupon-usages?couponCode=WELCOME10&status=CONSUMED&page=0&size=20")
            .header("X-Correlation-Id", "corr-gateway-coupon-usages")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-coupon-usages");
        assertThat(response.body()).contains("\"orderId\":\"ord_coupon_gateway_001\"");
        assertThat(response.body()).contains("\"couponCode\":\"WELCOME10\"");

        RecordedRequest forwarded = STUB_PROMOTION_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("GET");
        assertThat(forwarded.path()).isEqualTo("/api/admin/coupon-usages");
        assertThat(forwarded.query()).contains("couponCode=WELCOME10&status=CONSUMED&page=0&size=20");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-coupon-usages");
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_READ_MODEL_SERVICE.assertNoRequests();
    }

    @Test
    void routes_admin_coupon_usage_history_with_trailing_slash_to_promotion_service() throws Exception {
        HttpRequest request = adminRequest("/api/admin/coupon-usages/?status=RELEASED")
            .header("X-Correlation-Id", "corr-gateway-coupon-usages-slash")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-coupon-usages-slash");
        assertThat(response.body()).contains("\"couponCode\":\"WELCOME10\"");

        RecordedRequest forwarded = STUB_PROMOTION_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("GET");
        assertThat(forwarded.path()).isEqualTo("/api/admin/coupon-usages");
        assertThat(forwarded.query()).contains("status=RELEASED");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-coupon-usages-slash");
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_READ_MODEL_SERVICE.assertNoRequests();
    }

    @Test
    void routes_admin_fulfillment_request_history_to_fulfillment_service() throws Exception {
        HttpRequest request = adminRequest("/api/admin/fulfillment-requests?orderId=ord_gateway_fulfillment_001&status=PREPARING&page=0&size=20")
            .header("X-Correlation-Id", "corr-gateway-fulfillment-requests")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-fulfillment-requests");
        assertThat(response.body()).contains("\"orderId\":\"ord_gateway_fulfillment_001\"");
        assertThat(response.body()).contains("\"status\":\"PREPARING\"");

        RecordedRequest forwarded = STUB_FULFILLMENT_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("GET");
        assertThat(forwarded.path()).isEqualTo("/api/admin/fulfillment-requests");
        assertThat(forwarded.query()).contains("orderId=ord_gateway_fulfillment_001&status=PREPARING&page=0&size=20");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-fulfillment-requests");
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_PROMOTION_SERVICE.assertNoRequests();
        STUB_READ_MODEL_SERVICE.assertNoRequests();
    }

    @Test
    void routes_admin_fulfillment_request_history_with_trailing_slash_to_fulfillment_service() throws Exception {
        HttpRequest request = adminRequest("/api/admin/fulfillment-requests/?status=PREPARING")
            .header("X-Correlation-Id", "corr-gateway-fulfillment-requests-slash")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-fulfillment-requests-slash");
        assertThat(response.body()).contains("\"orderId\":\"ord_gateway_fulfillment_001\"");

        RecordedRequest forwarded = STUB_FULFILLMENT_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("GET");
        assertThat(forwarded.path()).isEqualTo("/api/admin/fulfillment-requests");
        assertThat(forwarded.query()).contains("status=PREPARING");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-fulfillment-requests-slash");
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_PROMOTION_SERVICE.assertNoRequests();
        STUB_READ_MODEL_SERVICE.assertNoRequests();
    }

    @Test
    void routes_customer_order_history_to_read_model_service() throws Exception {
        HttpRequest request = customerRequest("/api/read-model/orders?memberId=spoofed-member&page=0&size=10")
            .header("X-Correlation-Id", "corr-gateway-read-model-customer")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-read-model-customer");
        assertThat(response.body()).contains("\"memberId\":\"member-mobile\"");
        assertThat(response.body()).contains("\"orderId\":\"ord_read_model_001\"");

        RecordedRequest forwarded = STUB_READ_MODEL_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("GET");
        assertThat(forwarded.path()).isEqualTo("/api/read-model/orders");
        assertThat(forwarded.query()).doesNotContain("memberId=spoofed-member");
        assertThat(forwarded.query()).contains("page=0&size=10");
        assertThat(forwarded.firstHeader("X-StockRush-Subject")).contains("customer-demo");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-read-model-customer");
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_PROMOTION_SERVICE.assertNoRequests();
    }

    @Test
    void creates_missing_correlation_id_once_and_forwards_it_to_upstream() throws Exception {
        HttpRequest request = customerRequest("/api/read-model/orders?page=0&size=10")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        String generatedCorrelationId = response.headers()
            .firstValue("X-Correlation-Id")
            .orElseThrow();
        assertThat(generatedCorrelationId).isNotBlank();
        assertThat(generatedCorrelationId).isNotEqualTo("corr-gateway-outbox");
        assertThat(response.body()).contains("\"correlationId\":\"" + generatedCorrelationId + "\"");

        RecordedRequest forwarded = STUB_READ_MODEL_SERVICE.singleRequest();
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains(generatedCorrelationId);
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_PROMOTION_SERVICE.assertNoRequests();
    }

    @Test
    void keeps_gateway_correlation_header_when_upstream_response_header_differs() throws Exception {
        HttpRequest request = customerRequest("/api/read-model/orders?orderId=member-upstream-mutates-correlation&page=0&size=10")
            .header("X-Correlation-Id", "corr-gateway-preserved")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-preserved");

        RecordedRequest forwarded = STUB_READ_MODEL_SERVICE.singleRequest();
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-preserved");
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_PROMOTION_SERVICE.assertNoRequests();
    }

    @Test
    void routes_admin_order_summary_to_read_model_service() throws Exception {
        HttpRequest request = adminRequest("/api/read-model/admin/orders?status=CONFIRMED&page=1&size=5")
            .header("X-Correlation-Id", "corr-gateway-read-model-admin")
            .header("X-Operator-Id", "spoofed-operator")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("corr-gateway-read-model-admin");
        assertThat(response.body()).contains("\"status\":\"CONFIRMED\"");

        RecordedRequest forwarded = STUB_READ_MODEL_SERVICE.singleRequest();
        assertThat(forwarded.method()).isEqualTo("GET");
        assertThat(forwarded.path()).isEqualTo("/api/read-model/admin/orders");
        assertThat(forwarded.query()).contains("status=CONFIRMED&page=1&size=5");
        assertThat(forwarded.firstHeader("X-Correlation-Id")).contains("corr-gateway-read-model-admin");
        assertThat(forwarded.firstHeader("X-Operator-Id")).contains("admin-demo");
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_PROMOTION_SERVICE.assertNoRequests();
    }

    @Test
    void rejects_unknown_outbox_service_without_calling_upstream() throws Exception {
        HttpRequest request = adminRequest("/api/admin/outbox-services/finance/events")
            .header("X-Correlation-Id", "corr-gateway-unknown-outbox")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"SERVICE_ROUTE_NOT_FOUND\"");
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_INVENTORY_SERVICE.assertNoRequests();
        STUB_PAYMENT_SERVICE.assertNoRequests();
        STUB_PROMOTION_SERVICE.assertNoRequests();
        STUB_READ_MODEL_SERVICE.assertNoRequests();
    }

    private URI gatewayUri(String path) {
        return URI.create("http://localhost:" + gatewayPort + path);
    }

    private HttpRequest.Builder adminRequest(String path) {
        return HttpRequest.newBuilder(gatewayUri(path)).header("Authorization", "Bearer admin-token");
    }

    private HttpRequest.Builder customerRequest(String path) {
        return HttpRequest.newBuilder(gatewayUri(path)).header("Authorization", "Bearer customer-token");
    }

    @TestConfiguration
    static class TestJwtDecoderConfig {

        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return token -> switch (token) {
                case "admin-token" -> jwt(token, "admin-demo", List.of("ADMIN"));
                case "customer-token" -> jwt(token, "customer-demo", List.of("CUSTOMER"));
                default -> throw new BadJwtException("unsupported test token");
            };
        }

        private static Jwt jwt(String token, String subject, List<String> roles) {
            Instant now = Instant.parse("2026-05-15T00:00:00Z");
            return Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("realm_access", Map.of("roles", roles))
                .build();
        }
    }

    private static final class StubService {
        private final String serviceName;
        private final String eventType;
        private final String topic;
        private final Deque<RecordedRequest> requests = new ConcurrentLinkedDeque<>();
        private HttpServer server;

        StubService(String serviceName, String eventType, String topic) {
            this.serviceName = serviceName;
            this.eventType = eventType;
            this.topic = topic;
        }

        void start() {
            if (server != null) {
                return;
            }
            try {
                server = HttpServer.create(new InetSocketAddress(0), 0);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to start stub service", exception);
            }
            server.createContext("/api/orders", this::handle);
            server.createContext("/api/admin/orders", this::handle);
            server.createContext("/api/admin/outbox-events", this::handle);
            server.createContext("/api/admin/coupon-usages", this::handle);
            server.createContext("/api/admin/fulfillment-requests", this::handle);
            server.createContext("/api/admin/products", this::handle);
            server.createContext("/api/products", this::handle);
            server.createContext("/api/stocks", this::handle);
            server.createContext("/api/coupons", this::handle);
            server.createContext("/api/read-model", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        void reset() {
            requests.clear();
        }

        RecordedRequest singleRequest() {
            assertThat(requests).hasSize(1);
            return requests.peekFirst();
        }

        void assertNoRequests() {
            assertThat(requests).isEmpty();
        }

        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            requests.add(new RecordedRequest(exchange.getRequestMethod(), path, query, exchange.getRequestHeaders(), body));

            if ("GET".equals(exchange.getRequestMethod()) && "/api/admin/outbox-events".equals(path)) {
                writeJson(exchange, 200, currentCorrelationId(exchange), null, """
                    {"success":true,"data":{"service":"%s","limit":50,"offset":0,"items":[{"eventId":"018f8d0b-8d32-7c42-9f1b-78328e0f7b02","aggregateType":"%s","aggregateId":"agg_gateway_outbox","eventType":"%s","topic":"%s","partitionKey":"agg_gateway_outbox","payload":"{}","status":"PENDING","retryCount":0,"maxRetryCount":5,"createdAt":"2026-05-13T02:00:00Z","nextRetryAt":null,"publishedAt":null,"errorMessage":null}]},"error":null,"trace":{"correlationId":"%s"}}
                    """.formatted(serviceName, serviceName, eventType, topic, currentCorrelationId(exchange)));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/api/admin/outbox-events/retry".equals(path)) {
                int claimed = retryBatchSize(query);
                writeJson(exchange, 200, currentCorrelationId(exchange), null, """
                    {"success":true,"data":{"service":"%s","claimed":%d,"published":%d,"failed":0},"error":null,"trace":{"correlationId":"%s"}}
                    """.formatted(serviceName, claimed, claimed, currentCorrelationId(exchange)));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/api/admin/outbox-events/failed/requeue".equals(path)) {
                int updated = retryBatchSize(query);
                writeJson(exchange, 200, currentCorrelationId(exchange), null, """
                    {"success":true,"data":{"service":"%s","updated":%d},"error":null,"trace":{"correlationId":"%s"}}
                    """.formatted(serviceName, updated, currentCorrelationId(exchange)));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/api/products".equals(path)) {
                writeJson(exchange, 200, currentCorrelationId(exchange), null, """
                    {"success":true,"data":[{"productCode":"PROD-001","name":"Gateway Product","status":"ON_SALE","listPrice":12300}],"error":null,"trace":{"correlationId":"%s"}}
                    """.formatted(currentCorrelationId(exchange)));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/api/admin/products".equals(path)) {
                writeJson(exchange, 201, currentCorrelationId(exchange), null, """
                    {"success":true,"data":{"productCode":"PROD-007","name":"Gateway Test Product","status":"ON_SALE","listPrice":12300},"error":null,"trace":{"correlationId":"%s"}}
                    """.formatted(currentCorrelationId(exchange)));
                return;
            }
            if ("PUT".equals(exchange.getRequestMethod()) && path.startsWith("/api/admin/products/")) {
                writeJson(exchange, 200, currentCorrelationId(exchange), null, """
                    {"success":true,"data":{"productCode":"PROD-007","name":"Gateway Test Product Updated","status":"ON_SALE","listPrice":15900},"error":null,"trace":{"correlationId":"%s"}}
                    """.formatted(currentCorrelationId(exchange)));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/api/stocks".equals(path)) {
                writeJson(exchange, 200, currentCorrelationId(exchange), null, """
                    {"success":true,"data":[{"skuId":"SKU-007","productCode":"PROD-001","availableQuantity":20,"reservedQuantity":0,"version":1}],"error":null,"trace":{"correlationId":"%s"}}
                    """.formatted(currentCorrelationId(exchange)));
                return;
            }
            if ("PUT".equals(exchange.getRequestMethod()) && path.startsWith("/api/stocks/")) {
                writeJson(exchange, 200, currentCorrelationId(exchange), null, """
                    {"success":true,"data":{"skuId":"SKU-007","productCode":"PROD-001","availableQuantity":20,"reservedQuantity":0,"version":2},"error":null,"trace":{"correlationId":"%s"}}
                    """.formatted(currentCorrelationId(exchange)));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/api/orders".equals(path)) {
                writeJson(exchange, 201, currentCorrelationId(exchange), "/api/orders/ord_gateway_001", """
                    {"success":true,"data":{"orderId":"ord_gateway_001","status":"CREATED","sagaStatus":"STARTED","paymentMethod":"CARD","totalAmount":12000.0},"error":null,"trace":{"correlationId":"%s"}}
                    """.formatted(currentCorrelationId(exchange)));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/api/orders/ord_gateway_001".equals(path)) {
                writeJson(exchange, 200, "corr-gateway-query", null, """
                    {"success":true,"data":{"orderId":"ord_gateway_001","memberId":"member-gateway","status":"CONFIRMED","sagaStatus":"COMPLETED","paymentMethod":"CARD","totalAmount":12000.0,"items":[]},"error":null,"trace":{"correlationId":"corr-gateway-query"}}
                    """);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/api/admin/orders".equals(path)) {
                writeJson(exchange, 200, "corr-gateway-admin-list", null, """
                    {"success":true,"data":{"page":0,"size":5,"items":[{"orderId":"ord_gateway_delay","memberId":"member-gateway","status":"CREATED","sagaStatus":"PAYMENT_DELAYED","paymentMethod":"DELAY_CARD","totalAmount":12000.0,"itemCount":1,"createdAt":"2026-05-13T02:00:00Z","updatedAt":"2026-05-13T02:01:00Z"}]},"error":null,"trace":{"correlationId":"corr-gateway-admin-list"}}
                    """);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/api/admin/orders/ord_gateway_delay/saga".equals(path)) {
                writeJson(exchange, 200, "corr-gateway-admin-saga", null, """
                    {"success":true,"data":{"orderId":"ord_gateway_delay","orderStatus":"CREATED","sagaStatus":"PAYMENT_DELAYED","failedAt":null,"businessReason":null,"technicalErrorMessage":null,"lastEventType":"PaymentAuthorizationDelayed","outboxAttempts":0},"error":null,"trace":{"correlationId":"corr-gateway-admin-saga"}}
                    """);
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/api/admin/orders/ord_gateway_delay/cancel".equals(path)) {
                writeJson(exchange, 202, "corr-gateway-admin-cancel", null, """
                    {"success":true,"data":{"orderId":"ord_gateway_delay","status":"CREATED","sagaStatus":"PAYMENT_CANCEL_REQUESTED"},"error":null,"trace":{"correlationId":"corr-gateway-admin-cancel"}}
                    """);
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/api/coupons/quote".equals(path)) {
                writeJson(exchange, 200, currentCorrelationId(exchange), null, """
                    {"success":true,"data":{"couponCode":"WELCOME10","applied":true,"discountAmount":5000.0,"payAmount":75000.0,"reason":"APPLIED"},"error":null,"trace":{"correlationId":"%s"}}
                    """.formatted(currentCorrelationId(exchange)));
                return;
            }
            if (
                "GET".equals(exchange.getRequestMethod())
                    && ("/api/admin/coupon-usages".equals(path) || "/api/admin/coupon-usages/".equals(path))
            ) {
                writeJson(exchange, 200, currentCorrelationId(exchange), null, """
                    {"success":true,"data":{"page":0,"size":20,"items":[{"orderId":"ord_coupon_gateway_001","memberId":"member-gateway","couponCode":"WELCOME10","status":"CONSUMED","orderAmount":80000.0,"discountAmount":5000.0,"payableAmount":75000.0,"reservedAt":"2026-05-13T04:30:00Z","consumedAt":"2026-05-13T04:31:00Z","releasedAt":null,"releaseReason":null,"updatedAt":"2026-05-13T04:31:00Z"}]},"error":null,"trace":{"correlationId":"%s"}}
                    """.formatted(currentCorrelationId(exchange)));
                return;
            }
            if (
                "GET".equals(exchange.getRequestMethod())
                    && ("/api/admin/fulfillment-requests".equals(path) || "/api/admin/fulfillment-requests/".equals(path))
            ) {
                writeJson(exchange, 200, currentCorrelationId(exchange), null, """
                    {"success":true,"data":{"page":0,"size":20,"items":[{"requestId":"018f8d0b-8d32-7c42-9f1b-78328e0f801","orderId":"ord_gateway_fulfillment_001","status":"PREPARING","requestedAt":"2026-05-13T08:10:00Z","sourceEventId":"018f8d0b-8d32-7c42-9f1b-78328e0f7a1","correlationId":"corr-gateway-fulfillment-001","idempotencyKey":"idem-gateway-fulfillment-001","createdAt":"2026-05-13T08:10:00Z","updatedAt":"2026-05-13T08:10:00Z"}]},"error":null,"trace":{"correlationId":"%s"}}
                    """.formatted(currentCorrelationId(exchange)));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/api/read-model/orders".equals(path)) {
                if (query != null && query.contains("member-upstream-mutates-correlation")) {
                    writeJson(exchange, 200, "corr-upstream-mutated", null, """
                        {"success":true,"data":{"page":0,"size":10,"items":[{"orderId":"ord_read_model_001","memberId":"member-upstream-mutates-correlation","status":"CONFIRMED","sagaStatus":"COMPLETED","couponCode":"WELCOME10","totalAmount":80000.0,"discountAmount":5000.0,"payableAmount":75000.0,"itemCount":1}]},"error":null,"trace":{"correlationId":"%s"}}
                        """.formatted(currentCorrelationId(exchange)));
                    return;
                }
                writeJson(exchange, 200, currentCorrelationId(exchange), null, """
                    {"success":true,"data":{"page":0,"size":10,"items":[{"orderId":"ord_read_model_001","memberId":"member-mobile","status":"CONFIRMED","sagaStatus":"COMPLETED","couponCode":"WELCOME10","totalAmount":80000.0,"discountAmount":5000.0,"payableAmount":75000.0,"itemCount":1}]},"error":null,"trace":{"correlationId":"%s"}}
                    """.formatted(currentCorrelationId(exchange)));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/api/read-model/admin/orders".equals(path)) {
                writeJson(exchange, 200, currentCorrelationId(exchange), null, """
                    {"success":true,"data":{"page":1,"size":5,"items":[{"orderId":"ord_read_model_admin_001","memberId":"member-admin","status":"CONFIRMED","sagaStatus":"COMPLETED","totalAmount":80000.0,"discountAmount":5000.0,"payableAmount":75000.0,"itemCount":1}]},"error":null,"trace":{"correlationId":"%s"}}
                    """.formatted(currentCorrelationId(exchange)));
                return;
            }

            writeJson(exchange, 404, "corr-gateway-missing", null, """
                {"success":false,"data":null,"error":{"code":"ORDER_NOT_FOUND","message":"not found"},"trace":{"correlationId":"corr-gateway-missing"}}
                """);
        }

        private String currentCorrelationId(HttpExchange exchange) {
            return exchange.getRequestHeaders()
                .entrySet()
                .stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("X-Correlation-Id"))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse("corr-gateway-outbox");
        }

        private int retryBatchSize(String query) {
            if (query == null || query.isBlank()) {
                return 10;
            }
            for (String part : query.split("&")) {
                if (part.startsWith("batchSize=")) {
                    return Integer.parseInt(part.substring("batchSize=".length()));
                }
            }
            return 10;
        }

        private void writeJson(
            HttpExchange exchange,
            int status,
            String correlationId,
            String location,
            String body
        ) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Correlation-Id", correlationId);
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
            }
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    private record RecordedRequest(
        String method,
        String path,
        String query,
        Map<String, List<String>> headers,
        String body
    ) {
        Optional<String> firstHeader(String name) {
            return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst();
        }
    }
}
