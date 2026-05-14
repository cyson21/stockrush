package com.stockrush.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
        HttpServletRequest request
    ) {
        return gatewayServiceProxy.forward(
            ServiceRoute.READ_MODEL,
            "GET",
            withQueryString("/api/read-model/orders", request),
            headers,
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
}
