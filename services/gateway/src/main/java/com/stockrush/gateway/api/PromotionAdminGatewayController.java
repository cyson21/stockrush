package com.stockrush.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
/**
 * 외부 요청을 받아 대상 서비스에 위임하고, 상관관계/권한 정보를 보존해 응답을 표준화하는 게이트웨이 진입점입니다.
 */


@RestController
@RequestMapping("/api/admin")
class PromotionAdminGatewayController {

    private final GatewayServiceProxy gatewayServiceProxy;

    PromotionAdminGatewayController(GatewayServiceProxy gatewayServiceProxy) {
        this.gatewayServiceProxy = gatewayServiceProxy;
    }

    @GetMapping({"/coupon-usages", "/coupon-usages/"})
    ResponseEntity<String> listCouponUsages(
        @RequestHeader HttpHeaders headers,
        @AuthenticationPrincipal Jwt jwt,
        HttpServletRequest request
    ) {
        return gatewayServiceProxy.forward(
            ServiceRoute.PROMOTION,
            "GET",
            withQueryString("/api/admin/coupon-usages", request),
            TrustedIdentityHeaders.admin(headers, jwt),
            null
        );
    }

    @PostMapping("/coupons")
    ResponseEntity<String> createCoupon(
        @RequestHeader HttpHeaders headers,
        @AuthenticationPrincipal Jwt jwt,
        @RequestBody(required = false) String body
    ) {
        return gatewayServiceProxy.forward(
            ServiceRoute.PROMOTION,
            "POST",
            "/api/admin/coupons",
            TrustedIdentityHeaders.admin(headers, jwt),
            body
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
