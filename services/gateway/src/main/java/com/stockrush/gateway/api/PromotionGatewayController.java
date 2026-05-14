package com.stockrush.gateway.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coupons")
class PromotionGatewayController {

    private final GatewayServiceProxy gatewayServiceProxy;

    PromotionGatewayController(GatewayServiceProxy gatewayServiceProxy) {
        this.gatewayServiceProxy = gatewayServiceProxy;
    }

    @PostMapping("/quote")
    ResponseEntity<String> quote(
        @RequestHeader HttpHeaders headers,
        @RequestBody(required = false) String body
    ) {
        return gatewayServiceProxy.forward(ServiceRoute.PROMOTION, "POST", "/api/coupons/quote", headers, body);
    }
}
