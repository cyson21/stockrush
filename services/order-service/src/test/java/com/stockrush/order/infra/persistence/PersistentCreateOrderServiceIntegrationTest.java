package com.stockrush.order.infra.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.stockrush.order.application.CouponQuoteClient;
import com.stockrush.order.application.CouponQuoteResult;
import com.stockrush.order.application.CreateOrderCommand;
import com.stockrush.order.application.CreateOrderItemCommand;
import com.stockrush.order.application.CreateOrderResult;
import com.stockrush.order.application.OrderIdGenerator;
import com.stockrush.order.application.PersistentCreateOrderService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=orders",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class PersistentCreateOrderServiceIntegrationTest {

    @Autowired
    private PersistentCreateOrderService service;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("delete from outbox_events").update();
        jdbcClient.sql("delete from order_items").update();
        jdbcClient.sql("delete from customer_orders").update();
    }

    @Test
    void persists_order_items_and_pending_outbox_event_together() {
        CreateOrderCommand command = new CreateOrderCommand(
            "member-1",
            "idem-001",
            "corr-001",
            "FAIL_CARD",
            List.of(new CreateOrderItemCommand("LIMITED-001", "SKU-001", 2, new BigDecimal("12000.00")))
        );

        CreateOrderResult result = service.create(command);

        assertEquals(1, count("customer_orders"));
        assertEquals("FAIL_CARD", jdbcClient.sql("select payment_method from customer_orders").query(String.class).single());
        assertEquals(new BigDecimal("0.00"), jdbcClient.sql("select discount_amount from customer_orders").query(BigDecimal.class).single());
        assertEquals(new BigDecimal("24000.00"), jdbcClient.sql("select payable_amount from customer_orders").query(BigDecimal.class).single());
        assertEquals(1, count("order_items"));
        assertEquals(1, count("outbox_events"));
        assertEquals("ord_test_001", result.order().orderId());
        assertEquals("PENDING", queryString("select status from outbox_events where aggregate_id = :orderId", result.order().orderId()));
        assertEquals(
            "ord_test_001",
            queryString("select payload ->> 'orderId' from outbox_events where aggregate_id = :orderId", result.order().orderId())
        );
        assertEquals(
            "SKU-001",
            queryString("select payload #>> '{items,0,skuId}' from outbox_events where aggregate_id = :orderId", result.order().orderId())
        );
        assertEquals(
            "24000.00",
            queryString("select payload ->> 'payableAmount' from outbox_events where aggregate_id = :orderId", result.order().orderId())
        );
    }

    @Test
    void persists_coupon_pricing_snapshot() {
        CreateOrderCommand command = new CreateOrderCommand(
            "member-1",
            "idem-coupon-001",
            "corr-coupon-001",
            "CARD",
            "WELCOME10",
            List.of(new CreateOrderItemCommand("LIMITED-001", "SKU-001", 2, new BigDecimal("12000.00")))
        );

        CreateOrderResult result = service.create(command);

        assertEquals("WELCOME10", queryString("select coupon_code from customer_orders where order_id = :orderId", result.order().orderId()));
        assertEquals(
            new BigDecimal("3000.00"),
            jdbcClient.sql("select discount_amount from customer_orders where order_id = :orderId")
                .param("orderId", result.order().orderId())
                .query(BigDecimal.class)
                .single()
        );
        assertEquals(
            new BigDecimal("21000.00"),
            jdbcClient.sql("select payable_amount from customer_orders where order_id = :orderId")
                .param("orderId", result.order().orderId())
                .query(BigDecimal.class)
                .single()
        );
        assertEquals(
            "WELCOME10",
            queryString("select payload ->> 'couponCode' from outbox_events where aggregate_id = :orderId", result.order().orderId())
        );
        assertEquals(
            "3000.00",
            queryString("select payload ->> 'discountAmount' from outbox_events where aggregate_id = :orderId", result.order().orderId())
        );
        assertEquals(
            "21000.00",
            queryString("select payload ->> 'payableAmount' from outbox_events where aggregate_id = :orderId", result.order().orderId())
        );
    }

    private int count(String tableName) {
        return jdbcClient.sql("select count(*) from " + tableName).query(Integer.class).single();
    }

    private String queryString(String sql, String orderId) {
        return jdbcClient.sql(sql).param("orderId", orderId).query(String.class).single();
    }

    @TestConfiguration
    static class FixedIdsConfig {

        @Bean
        @Primary
        OrderIdGenerator fixedOrderIdGenerator() {
            return () -> "ord_test_001";
        }

        @Bean
        @Primary
        Supplier<UUID> fixedEventIdSupplier() {
            return () -> UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7a11");
        }

        @Bean
        @Primary
        CouponQuoteClient fixedCouponQuoteClient() {
            return (couponCode, orderAmount, correlationId) -> new CouponQuoteResult(
                couponCode,
                true,
                new BigDecimal("3000.00"),
                orderAmount.subtract(new BigDecimal("3000.00")),
                "APPLIED"
            );
        }
    }
}
