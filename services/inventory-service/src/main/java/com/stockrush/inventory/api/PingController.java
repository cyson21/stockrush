package com.stockrush.inventory.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PingController {

    @GetMapping("/internal/ping")
    PingResponse ping() {
        return new PingResponse("inventory-service", "ok");
    }
}

record PingResponse(String service, String status) {
}

