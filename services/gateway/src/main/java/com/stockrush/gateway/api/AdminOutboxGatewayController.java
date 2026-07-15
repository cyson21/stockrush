package com.stockrush.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
/**
 * 외부 요청을 받아 대상 서비스에 위임하고, 상관관계/권한 정보를 보존해 응답을 표준화하는 게이트웨이 진입점입니다.
 */


@RestController
@RequestMapping("/api/admin/outbox-services/{service}/events")
class AdminOutboxGatewayController {

    private final GatewayServiceProxy gatewayServiceProxy;

    AdminOutboxGatewayController(GatewayServiceProxy gatewayServiceProxy) {
        this.gatewayServiceProxy = gatewayServiceProxy;
    }

    @GetMapping
    ResponseEntity<String> list(
        @PathVariable String service,
        @RequestHeader HttpHeaders headers,
        @AuthenticationPrincipal Jwt jwt,
        HttpServletRequest request
    ) {
        return forward(
            service,
            "GET",
            withQueryString("/api/admin/outbox-events", request),
            TrustedIdentityHeaders.admin(headers, jwt),
            null
        );
    }

    @PostMapping("/retry")
    ResponseEntity<String> retry(
        @PathVariable String service,
        @RequestHeader HttpHeaders headers,
        @AuthenticationPrincipal Jwt jwt,
        HttpServletRequest request
    ) {
        return forward(
            service,
            "POST",
            withQueryString("/api/admin/outbox-events/retry", request),
            TrustedIdentityHeaders.admin(headers, jwt),
            null
        );
    }

    @PostMapping("/failed/requeue")
    ResponseEntity<String> requeueFailed(
        @PathVariable String service,
        @RequestHeader HttpHeaders headers,
        @AuthenticationPrincipal Jwt jwt,
        HttpServletRequest request
    ) {
        return forward(
            service,
            "POST",
            withQueryString("/api/admin/outbox-events/failed/requeue", request),
            TrustedIdentityHeaders.admin(headers, jwt),
            null
        );
    }

    private ResponseEntity<String> forward(
        String service,
        String method,
        String path,
        HttpHeaders headers,
        String body
    ) {
        try {
            return gatewayServiceProxy.forward(ServiceRoute.from(service), method, path, headers, body);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("""
                {"success":false,"data":null,"error":{"code":"SERVICE_ROUTE_NOT_FOUND","message":"unsupported service route"},"trace":{"correlationId":null}}
                """);
        }
    }

    private String withQueryString(String path, HttpServletRequest request) {
        String queryString = request.getQueryString();
        if (queryString == null || queryString.isBlank()) {
            return path;
        }
        return path + "?" + queryString;
    }
}
