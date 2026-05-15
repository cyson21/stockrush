package com.stockrush.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/read-model")
class ReadModelGatewayController {

    private final GatewayServiceProxy gatewayServiceProxy;

    ReadModelGatewayController(GatewayServiceProxy gatewayServiceProxy) {
        this.gatewayServiceProxy = gatewayServiceProxy;
    }

    @GetMapping("/orders")
    ResponseEntity<String> getCustomerOrders(
        @RequestHeader HttpHeaders headers,
        @AuthenticationPrincipal Jwt jwt,
        HttpServletRequest request
    ) {
        return gatewayServiceProxy.forward(
            ServiceRoute.READ_MODEL,
            "GET",
            withCustomerQueryString("/api/read-model/orders", request),
            TrustedIdentityHeaders.customer(headers, jwt),
            null
        );
    }

    @GetMapping("/admin/orders")
    ResponseEntity<String> getAdminOrders(
        @RequestHeader HttpHeaders headers,
        HttpServletRequest request
    ) {
        return gatewayServiceProxy.forward(
            ServiceRoute.READ_MODEL,
            "GET",
            withQueryString("/api/read-model/admin/orders", request),
            headers,
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

    private String withCustomerQueryString(String path, HttpServletRequest request) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if ("memberId".equals(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (query.length() > 0) {
                    query.append('&');
                }
                query.append(encode(entry.getKey())).append('=').append(encode(value));
            }
        }
        if (query.isEmpty()) {
            return path;
        }
        return path + "?" + query;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
