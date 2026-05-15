package com.stockrush.gateway.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
class GatewayServiceProxy {

    private static final Set<String> REQUEST_HEADERS = Set.of(
        "content-type",
        "idempotency-key",
        "x-correlation-id",
        "x-operator-id",
        "x-stockrush-subject",
        "x-stockrush-email",
        "x-stockrush-roles"
    );
    private static final Set<String> RESPONSE_HEADERS = Set.of(
        "content-type",
        "location",
        "x-correlation-id"
    );

    private final HttpClient httpClient;
    private final GatewayRoutingProperties routingProperties;

    @Autowired
    GatewayServiceProxy(GatewayRoutingProperties routingProperties) {
        this(HttpClient.newHttpClient(), routingProperties);
    }

    GatewayServiceProxy(HttpClient httpClient, GatewayRoutingProperties routingProperties) {
        this.httpClient = httpClient;
        this.routingProperties = routingProperties;
    }

    ResponseEntity<String> forward(String method, String path, HttpHeaders requestHeaders, String body) {
        return forward(ServiceRoute.ORDER, method, path, requestHeaders, body);
    }

    ResponseEntity<String> forward(
        ServiceRoute service,
        String method,
        String path,
        HttpHeaders requestHeaders,
        String body
    ) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(routingProperties.serviceUrl(service) + path));
        copyRequestHeaders(requestHeaders, requestBuilder);
        String correlationId = requestHeaders.getFirst("X-Correlation-Id");

        HttpRequest.BodyPublisher bodyPublisher = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = requestBuilder.method(method, bodyPublisher).build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return toResponseEntity(response, correlationId);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to route request to upstream service", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while routing request to upstream service", exception);
        }
    }

    private void copyRequestHeaders(HttpHeaders source, HttpRequest.Builder target) {
        source.forEach((name, values) -> {
            if (REQUEST_HEADERS.contains(name.toLowerCase())) {
                for (String value : values) {
                    target.header(name, value);
                }
            }
        });
    }

    private ResponseEntity<String> toResponseEntity(HttpResponse<String> response, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        response.headers().map().forEach((name, values) -> {
            if (RESPONSE_HEADERS.contains(name.toLowerCase())) {
                headers.put(name, List.copyOf(values));
            }
        });
        if (correlationId != null && !correlationId.isBlank()) {
            headers.set("X-Correlation-Id", correlationId);
        }
        return new ResponseEntity<>(response.body(), headers, HttpStatus.valueOf(response.statusCode()));
    }
}

enum ServiceRoute {
    CATALOG,
    ORDER,
    INVENTORY,
    PAYMENT,
    PROMOTION,
    FULFILLMENT,
    READ_MODEL;

    static ServiceRoute from(String value) {
        return switch (value) {
            case "catalog" -> CATALOG;
            case "order" -> ORDER;
            case "inventory" -> INVENTORY;
            case "payment" -> PAYMENT;
            case "promotion" -> PROMOTION;
            case "fulfillment" -> FULFILLMENT;
            case "read-model" -> READ_MODEL;
            default -> throw new IllegalArgumentException("unsupported service route: " + value);
        };
    }
}

@ConfigurationProperties(prefix = "stockrush.routes")
record GatewayRoutingProperties(
    String catalogServiceUrl,
    String orderServiceUrl,
    String inventoryServiceUrl,
    String paymentServiceUrl,
    String promotionServiceUrl,
    String fulfillmentServiceUrl,
    String readModelServiceUrl
) {

    private static final String DEFAULT_CATALOG_SERVICE_URL = "http://localhost:18081";
    private static final String DEFAULT_ORDER_SERVICE_URL = "http://localhost:18083";
    private static final String DEFAULT_INVENTORY_SERVICE_URL = "http://localhost:18082";
    private static final String DEFAULT_PAYMENT_SERVICE_URL = "http://localhost:18084";
    private static final String DEFAULT_PROMOTION_SERVICE_URL = "http://localhost:18085";
    private static final String DEFAULT_FULFILLMENT_SERVICE_URL = "http://localhost:18086";
    private static final String DEFAULT_READ_MODEL_SERVICE_URL = "http://localhost:18087";

    GatewayRoutingProperties {
        catalogServiceUrl = normalize(catalogServiceUrl, DEFAULT_CATALOG_SERVICE_URL);
        orderServiceUrl = normalize(orderServiceUrl, DEFAULT_ORDER_SERVICE_URL);
        inventoryServiceUrl = normalize(inventoryServiceUrl, DEFAULT_INVENTORY_SERVICE_URL);
        paymentServiceUrl = normalize(paymentServiceUrl, DEFAULT_PAYMENT_SERVICE_URL);
        promotionServiceUrl = normalize(promotionServiceUrl, DEFAULT_PROMOTION_SERVICE_URL);
        fulfillmentServiceUrl = normalize(fulfillmentServiceUrl, DEFAULT_FULFILLMENT_SERVICE_URL);
        readModelServiceUrl = normalize(readModelServiceUrl, DEFAULT_READ_MODEL_SERVICE_URL);
    }

    String serviceUrl(ServiceRoute service) {
        return switch (service) {
            case CATALOG -> catalogServiceUrl;
            case ORDER -> orderServiceUrl;
            case INVENTORY -> inventoryServiceUrl;
            case PAYMENT -> paymentServiceUrl;
            case PROMOTION -> promotionServiceUrl;
            case FULFILLMENT -> fulfillmentServiceUrl;
            case READ_MODEL -> readModelServiceUrl;
        };
    }

    private static String normalize(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.replaceAll("/+$", "");
    }
}
