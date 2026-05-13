package com.stockrush.promotion.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PingController {

    @GetMapping("/ping")
    PingResponse ping() {
        return new PingResponse("promotion-service", "ok");
    }
}

record PingResponse(String service, String status) {
}
