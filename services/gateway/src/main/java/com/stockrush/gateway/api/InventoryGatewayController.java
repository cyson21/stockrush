package com.stockrush.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;
/**
 * 외부 요청을 받아 대상 서비스에 위임하고, 상관관계/권한 정보를 보존해 응답을 표준화하는 게이트웨이 진입점입니다.
 */


@RestController
@RequestMapping("/api/stocks")
class InventoryGatewayController {

    private final GatewayServiceProxy gatewayServiceProxy;

    InventoryGatewayController(GatewayServiceProxy gatewayServiceProxy) {
        this.gatewayServiceProxy = gatewayServiceProxy;
    }

    @GetMapping({"", "/"})
    ResponseEntity<String> list(
        @RequestHeader HttpHeaders headers,
        HttpServletRequest request
    ) {
        return gatewayServiceProxy.forward(
            ServiceRoute.INVENTORY,
            "GET",
            withQueryString("/api/stocks", request),
            TrustedIdentityHeaders.publicRequest(headers),
            null
        );
    }

    @GetMapping("/{skuId}")
    ResponseEntity<String> getDetail(
        @PathVariable String skuId,
        @RequestHeader HttpHeaders headers
    ) {
        String encodedSkuId = UriUtils.encodePathSegment(skuId, StandardCharsets.UTF_8);
        return gatewayServiceProxy.forward(
            ServiceRoute.INVENTORY,
            "GET",
            "/api/stocks/" + encodedSkuId,
            TrustedIdentityHeaders.publicRequest(headers),
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
