package com.stockrush.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/fulfillment-requests")
class FulfillmentAdminGatewayController {

    private final GatewayServiceProxy gatewayServiceProxy;

    FulfillmentAdminGatewayController(GatewayServiceProxy gatewayServiceProxy) {
        this.gatewayServiceProxy = gatewayServiceProxy;
    }

    @GetMapping({"", "/"})
    ResponseEntity<String> listRequests(
        @RequestHeader HttpHeaders headers,
        HttpServletRequest request
    ) {
        return gatewayServiceProxy.forward(
            ServiceRoute.FULFILLMENT,
            "GET",
            withQueryString("/api/admin/fulfillment-requests", request),
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
