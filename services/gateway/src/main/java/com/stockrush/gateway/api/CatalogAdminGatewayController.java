package com.stockrush.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

@RestController
@RequestMapping("/api/admin/products")
class CatalogAdminGatewayController {

    private final GatewayServiceProxy gatewayServiceProxy;

    CatalogAdminGatewayController(GatewayServiceProxy gatewayServiceProxy) {
        this.gatewayServiceProxy = gatewayServiceProxy;
    }

    @GetMapping({"", "/"})
    ResponseEntity<String> list(
        @RequestHeader HttpHeaders headers,
        @AuthenticationPrincipal Jwt jwt,
        HttpServletRequest request
    ) {
        return gatewayServiceProxy.forward(
            ServiceRoute.CATALOG,
            "GET",
            withQueryString("/api/products", request),
            TrustedIdentityHeaders.admin(headers, jwt),
            null
        );
    }

    @PostMapping
    ResponseEntity<String> create(
        @RequestHeader HttpHeaders headers,
        @AuthenticationPrincipal Jwt jwt,
        @RequestBody(required = false) String body
    ) {
        return gatewayServiceProxy.forward(
            ServiceRoute.CATALOG,
            "POST",
            "/api/admin/products",
            TrustedIdentityHeaders.admin(headers, jwt),
            body
        );
    }

    @PutMapping("/{productCode}")
    ResponseEntity<String> update(
        @PathVariable String productCode,
        @RequestHeader HttpHeaders headers,
        @AuthenticationPrincipal Jwt jwt,
        @RequestBody(required = false) String body
    ) {
        String encodedProductCode = UriUtils.encodePathSegment(productCode, StandardCharsets.UTF_8);
        return gatewayServiceProxy.forward(
            ServiceRoute.CATALOG,
            "PUT",
            "/api/admin/products/" + encodedProductCode,
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
