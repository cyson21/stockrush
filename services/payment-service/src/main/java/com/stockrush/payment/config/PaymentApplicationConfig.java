package com.stockrush.payment.config;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/**
 * 공통 빈 초기화·유틸리티 등록을 통해 결제 서비스 동작 환경을 구성하는 구성 클래스입니다.
 */


@Configuration
class PaymentApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Supplier<UUID> idSupplier() {
        return UUID::randomUUID;
    }
}
