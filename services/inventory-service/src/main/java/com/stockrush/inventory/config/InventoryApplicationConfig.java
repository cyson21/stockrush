// InventoryApplicationConfig: 서비스 부트스트랩 진입 클래스이며 실행 주기를 관리합니다.

package com.stockrush.inventory.config;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class InventoryApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Supplier<UUID> idSupplier() {
        return UUID::randomUUID;
    }
}
