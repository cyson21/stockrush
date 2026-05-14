package com.stockrush.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/coupon-usages")
class PromotionAdminGatewayController {

    private final GatewayServiceProxy gatewayServiceProxy;

    PromotionAdminGatewayController(GatewayServiceProxy gatewayServiceProxy) {
        this.gatewayServiceProxy = gatewayServiceProxy;
    }

    @GetMapping({"", "/"})
    ResponseEntity<String> listCouponUsages(
        @RequestHeader HttpHeaders headers,
        HttpServletRequest request
    ) {
        return gatewayServiceProxy.forward(
            ServiceRoute.PROMOTION,
            "GET",
            withQueryString("/api/admin/coupon-usages", request),
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
