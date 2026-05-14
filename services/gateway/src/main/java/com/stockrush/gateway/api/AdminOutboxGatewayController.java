package com.stockrush.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/outbox-services/{service}/events")
class AdminOutboxGatewayController {

    private final GatewayServiceProxy gatewayServiceProxy;

    AdminOutboxGatewayController(GatewayServiceProxy gatewayServiceProxy) {
        this.gatewayServiceProxy = gatewayServiceProxy;
    }

    @GetMapping
    ResponseEntity<String> list(
        @PathVariable String service,
        @RequestHeader HttpHeaders headers,
        HttpServletRequest request
    ) {
        return forward(service, "GET", withQueryString("/api/admin/outbox-events", request), headers, null);
    }

    @PostMapping("/retry")
    ResponseEntity<String> retry(
        @PathVariable String service,
        @RequestHeader HttpHeaders headers,
        HttpServletRequest request
    ) {
        return forward(service, "POST", withQueryString("/api/admin/outbox-events/retry", request), headers, null);
    }

    @PostMapping("/failed/requeue")
    ResponseEntity<String> requeueFailed(
        @PathVariable String service,
        @RequestHeader HttpHeaders headers,
        HttpServletRequest request
    ) {
        return forward(service, "POST", withQueryString("/api/admin/outbox-events/failed/requeue", request), headers, null);
    }

    private ResponseEntity<String> forward(
        String service,
        String method,
        String path,
        HttpHeaders headers,
        String body
    ) {
        try {
            return gatewayServiceProxy.forward(ServiceRoute.from(service), method, path, headers, body);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("""
                {"success":false,"data":null,"error":{"code":"SERVICE_ROUTE_NOT_FOUND","message":"unsupported service route"},"trace":{"correlationId":null}}
                """);
        }
    }

    private String withQueryString(String path, HttpServletRequest request) {
        String queryString = request.getQueryString();
        if (queryString == null || queryString.isBlank()) {
            return path;
        }
        return path + "?" + queryString;
    }
}
