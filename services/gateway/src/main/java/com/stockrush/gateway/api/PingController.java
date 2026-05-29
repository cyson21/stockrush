package com.stockrush.gateway.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
/**
 * 외부 요청을 받아 대상 서비스에 위임하고, 상관관계/권한 정보를 보존해 응답을 표준화하는 게이트웨이 진입점입니다.
 */


@RestController
class PingController {

    @GetMapping("/internal/ping")
    PingResponse ping() {
        return new PingResponse("gateway", "ok");
    }
}

record PingResponse(String service, String status) {
}

