package com.stockrush.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;
/**
 * 외부 요청을 받아 대상 서비스에 위임하고, 상관관계/권한 정보를 보존해 응답을 표준화하는 게이트웨이 진입점입니다.
 */


@RestController
@RequestMapping("/api/admin/stocks")
class InventoryAdminGatewayController {

    private final GatewayServiceProxy gatewayServiceProxy;

    InventoryAdminGatewayController(GatewayServiceProxy gatewayServiceProxy) {
        this.gatewayServiceProxy = gatewayServiceProxy;
    }

    @GetMapping({"", "/"})
    ResponseEntity<String> list(
        @RequestHeader HttpHeaders headers,
        @AuthenticationPrincipal Jwt jwt,
        HttpServletRequest request
    ) {
        return gatewayServiceProxy.forward(
            ServiceRoute.INVENTORY,
            "GET",
            withQueryString("/api/stocks", request),
            TrustedIdentityHeaders.admin(headers, jwt),
            null
        );
    }

    @PutMapping("/{skuId}")
    ResponseEntity<String> setStock(
        @PathVariable String skuId,
        @RequestHeader HttpHeaders headers,
        @AuthenticationPrincipal Jwt jwt,
        @RequestBody(required = false) String body
    ) {
        String encodedSkuId = UriUtils.encodePathSegment(skuId, StandardCharsets.UTF_8);
        return gatewayServiceProxy.forward(
            ServiceRoute.INVENTORY,
            "PUT",
            "/api/stocks/" + encodedSkuId,
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
