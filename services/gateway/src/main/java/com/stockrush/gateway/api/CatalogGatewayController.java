package com.stockrush.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
/**
 * 외부 요청을 받아 대상 서비스에 위임하고, 상관관계/권한 정보를 보존해 응답을 표준화하는 게이트웨이 진입점입니다.
 */


@RestController
@RequestMapping("/api/products")
class CatalogGatewayController {

    private final GatewayServiceProxy gatewayServiceProxy;

    CatalogGatewayController(GatewayServiceProxy gatewayServiceProxy) {
        this.gatewayServiceProxy = gatewayServiceProxy;
    }

    @GetMapping({"", "/"})
    ResponseEntity<String> list(
        @RequestHeader HttpHeaders headers,
        HttpServletRequest request
    ) {
        return gatewayServiceProxy.forward(
            ServiceRoute.CATALOG,
            "GET",
            withQueryString("/api/products", request),
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
