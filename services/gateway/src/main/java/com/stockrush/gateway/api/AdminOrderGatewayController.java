package com.stockrush.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

@RestController
@RequestMapping("/api/admin/orders")
class AdminOrderGatewayController {

    private final OrderServiceProxy orderServiceProxy;

    AdminOrderGatewayController(OrderServiceProxy orderServiceProxy) {
        this.orderServiceProxy = orderServiceProxy;
    }

    @GetMapping
    ResponseEntity<String> list(
        @RequestHeader HttpHeaders headers,
        HttpServletRequest request
    ) {
        return orderServiceProxy.forward("GET", withQueryString("/api/admin/orders", request), headers, null);
    }

    @GetMapping("/{orderId}/saga")
    ResponseEntity<String> getSaga(
        @PathVariable String orderId,
        @RequestHeader HttpHeaders headers
    ) {
        String encodedOrderId = UriUtils.encodePathSegment(orderId, StandardCharsets.UTF_8);
        return orderServiceProxy.forward("GET", "/api/admin/orders/" + encodedOrderId + "/saga", headers, null);
    }

    @PostMapping("/{orderId}/cancel")
    ResponseEntity<String> cancel(
        @PathVariable String orderId,
        @RequestHeader HttpHeaders headers
    ) {
        String encodedOrderId = UriUtils.encodePathSegment(orderId, StandardCharsets.UTF_8);
        return orderServiceProxy.forward("POST", "/api/admin/orders/" + encodedOrderId + "/cancel", headers, null);
    }

    private String withQueryString(String path, HttpServletRequest request) {
        String queryString = request.getQueryString();
        if (queryString == null || queryString.isBlank()) {
            return path;
        }
        return path + "?" + queryString;
    }
}
