package com.stockrush.order.config;

import com.stockrush.order.application.CreateOrderService;
import com.stockrush.order.application.OrderIdGenerator;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OrderApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Supplier<UUID> eventIdSupplier() {
        return UUID::randomUUID;
    }

    @Bean
    OrderIdGenerator orderIdGenerator(Clock clock) {
        return () -> "ord_" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(clock.getZone())
            .format(clock.instant()) + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Bean
    CreateOrderService createOrderService(Clock clock, Supplier<UUID> eventIdSupplier, OrderIdGenerator orderIdGenerator) {
        return new CreateOrderService(clock, eventIdSupplier, orderIdGenerator);
    }
}

