package com.stockrush.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

    private final GatewayServiceProxy gatewayServiceProxy;

    AdminOrderGatewayController(GatewayServiceProxy gatewayServiceProxy) {
        this.gatewayServiceProxy = gatewayServiceProxy;
    }

    @GetMapping
    ResponseEntity<String> list(
        @RequestHeader HttpHeaders headers,
        @AuthenticationPrincipal Jwt jwt,
        HttpServletRequest request
    ) {
        return gatewayServiceProxy.forward(
            "GET",
            withQueryString("/api/admin/orders", request),
            TrustedIdentityHeaders.admin(headers, jwt),
            null
        );
    }

    @GetMapping("/{orderId}/saga")
    ResponseEntity<String> getSaga(
        @PathVariable String orderId,
        @RequestHeader HttpHeaders headers,
        @AuthenticationPrincipal Jwt jwt
    ) {
        String encodedOrderId = UriUtils.encodePathSegment(orderId, StandardCharsets.UTF_8);
        return gatewayServiceProxy.forward(
            "GET",
            "/api/admin/orders/" + encodedOrderId + "/saga",
            TrustedIdentityHeaders.admin(headers, jwt),
            null
        );
    }

    @PostMapping("/{orderId}/cancel")
    ResponseEntity<String> cancel(
        @PathVariable String orderId,
        @RequestHeader HttpHeaders headers,
        @AuthenticationPrincipal Jwt jwt
    ) {
        String encodedOrderId = UriUtils.encodePathSegment(orderId, StandardCharsets.UTF_8);
        return gatewayServiceProxy.forward(
            "POST",
            "/api/admin/orders/" + encodedOrderId + "/cancel",
            TrustedIdentityHeaders.admin(headers, jwt),
            null
        );
    }

    private String withQueryString(String path, HttpServletRequest request) {
        String queryString = request.getQueryString();
        if (queryString == null || queryString.isBlank()) {
            return path;
        }
        return path + "?" + queryString;
    }
}
