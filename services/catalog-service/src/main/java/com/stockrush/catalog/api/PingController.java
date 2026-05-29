// PingController: API 진입점으로 요청/응답 경계와 HTTP 흐름을 정리합니다.

package com.stockrush.catalog.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PingController {

    @GetMapping("/internal/ping")
    PingResponse ping() {
        return new PingResponse("catalog-service", "ok");
    }
}

record PingResponse(String service, String status) {
}

