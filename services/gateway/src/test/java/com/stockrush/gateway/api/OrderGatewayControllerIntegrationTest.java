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

    private static final StubOrderService STUB_ORDER_SERVICE = new StubOrderService();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        STUB_ORDER_SERVICE.start();
        registry.add("stockrush.routes.order-service-url", STUB_ORDER_SERVICE::baseUrl);
    }

    @BeforeEach
    void setUp() {
        STUB_ORDER_SERVICE.reset();
    }

    @AfterAll
    static void tearDown() {
        STUB_ORDER_SERVICE.stop();
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

    private URI gatewayUri(String path) {
        return URI.create("http://localhost:" + gatewayPort + path);
    }

    private static final class StubOrderService {
        private final Deque<RecordedRequest> requests = new ConcurrentLinkedDeque<>();
        private HttpServer server;

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

        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            requests.add(new RecordedRequest(exchange.getRequestMethod(), path, exchange.getRequestHeaders(), body));

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

            writeJson(exchange, 404, "corr-gateway-missing", null, """
                {"success":false,"data":null,"error":{"code":"ORDER_NOT_FOUND","message":"not found"},"trace":{"correlationId":"corr-gateway-missing"}}
                """);
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
