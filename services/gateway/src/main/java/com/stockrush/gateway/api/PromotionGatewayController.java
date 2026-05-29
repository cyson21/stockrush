package com.stockrush.gateway.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
/**
 * 외부 요청을 받아 대상 서비스에 위임하고, 상관관계/권한 정보를 보존해 응답을 표준화하는 게이트웨이 진입점입니다.
 */


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
