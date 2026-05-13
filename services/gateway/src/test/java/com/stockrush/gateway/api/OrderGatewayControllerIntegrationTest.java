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
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderGatewayControllerIntegrationTest {

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

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        STUB_ORDER_SERVICE.start();
        STUB_INVENTORY_SERVICE.start();
        STUB_PAYMENT_SERVICE.start();
        registry.add("stockrush.routes.order-service-url", STUB_ORDER_SERVICE::baseUrl);
        registry.add("stockrush.routes.inventory-service-url", STUB_INVENTORY_SERVICE::baseUrl);
        registry.add("stockrush.routes.payment-service-url", STUB_PAYMENT_SERVICE::baseUrl);
    }

    @BeforeEach
    void setUp() {
        STUB_ORDER_SERVICE.reset();
        STUB_INVENTORY_SERVICE.reset();
        STUB_PAYMENT_SERVICE.reset();
    }

    @AfterAll
    static void tearDown() {
        STUB_ORDER_SERVICE.stop();
        STUB_INVENTORY_SERVICE.stop();
        STUB_PAYMENT_SERVICE.stop();
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

        HttpRequest request = HttpRequest.newBuilder(gatewayUri("/api/orders"))
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
    void routes_order_detail_query_to_order_service() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(gatewayUri("/api/orders/ord_gateway_001"))
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
    void routes_admin_order_list_query_to_order_service() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                gatewayUri("/api/admin/orders?page=0&size=5&status=CREATED&sagaStatus=PAYMENT_DELAYED")
            )
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
        HttpRequest request = HttpRequest.newBuilder(gatewayUri("/api/admin/orders/ord_gateway_delay/saga"))
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
    void routes_admin_order_cancel_command_to_order_service() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(gatewayUri("/api/admin/orders/ord_gateway_delay/cancel"))
            .header("Idempotency-Key", "idem-gateway-admin-cancel")
            .header("X-Correlation-Id", "corr-gateway-admin-cancel")
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
    }

    @Test
    void routes_order_outbox_list_query_to_order_service() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                gatewayUri("/api/admin/outbox-services/order/events?status=PENDING,FAILED&limit=5&offset=0")
            )
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
        HttpRequest request = HttpRequest.newBuilder(
                gatewayUri("/api/admin/outbox-services/inventory/events/retry?batchSize=7")
            )
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
        HttpRequest request = HttpRequest.newBuilder(
                gatewayUri("/api/admin/outbox-services/payment/events/failed/requeue?batchSize=3")
            )
            .header("X-Correlation-Id", "corr-gateway-payment-requeue")
            .header("X-Operator-Id", "operator-gateway")
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
        assertThat(forwarded.firstHeader("X-Operator-Id")).contains("operator-gateway");
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_INVENTORY_SERVICE.assertNoRequests();
    }

    @Test
    void routes_payment_outbox_list_query_to_payment_service() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                gatewayUri("/api/admin/outbox-services/payment/events?status=FAILED")
            )
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
    void rejects_unknown_outbox_service_without_calling_upstream() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(gatewayUri("/api/admin/outbox-services/finance/events"))
            .header("X-Correlation-Id", "corr-gateway-unknown-outbox")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"SERVICE_ROUTE_NOT_FOUND\"");
        STUB_ORDER_SERVICE.assertNoRequests();
        STUB_INVENTORY_SERVICE.assertNoRequests();
        STUB_PAYMENT_SERVICE.assertNoRequests();
    }

    private URI gatewayUri(String path) {
        return URI.create("http://localhost:" + gatewayPort + path);
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
                throw new IllegalStateException("Failed to start stub order service", exception);
            }
            server.createContext("/api/orders", this::handle);
            server.createContext("/api/admin/orders", this::handle);
            server.createContext("/api/admin/outbox-events", this::handle);
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
            if ("POST".equals(exchange.getRequestMethod()) && "/api/orders".equals(path)) {
                writeJson(exchange, 201, "corr-gateway-create", "/api/orders/ord_gateway_001", """
                    {"success":true,"data":{"orderId":"ord_gateway_001","status":"CREATED","sagaStatus":"STARTED","paymentMethod":"CARD","totalAmount":12000.0},"error":null,"trace":{"correlationId":"corr-gateway-create"}}
                    """);
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
