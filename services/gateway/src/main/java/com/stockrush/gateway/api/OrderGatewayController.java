package com.stockrush.gateway.api;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

@RestController
@RequestMapping("/api/orders")
class OrderGatewayController {

    private final GatewayServiceProxy gatewayServiceProxy;

    OrderGatewayController(GatewayServiceProxy gatewayServiceProxy) {
        this.gatewayServiceProxy = gatewayServiceProxy;
    }

    @PostMapping
    ResponseEntity<String> create(
        @RequestHeader HttpHeaders headers,
        @RequestBody(required = false) String body
    ) {
        return gatewayServiceProxy.forward("POST", "/api/orders", headers, body);
    }

    @GetMapping("/{orderId}")
    ResponseEntity<String> getDetail(
        @PathVariable String orderId,
        @RequestHeader HttpHeaders headers
    ) {
        String encodedOrderId = UriUtils.encodePathSegment(orderId, StandardCharsets.UTF_8);
        return gatewayServiceProxy.forward("GET", "/api/orders/" + encodedOrderId, headers, null);
    }
}
