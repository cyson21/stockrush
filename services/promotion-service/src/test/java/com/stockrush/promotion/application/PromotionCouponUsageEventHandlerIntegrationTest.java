-- 도메인 초기화/보조 스키마 마이그레이션입니다.

package com.stockrush.promotion.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.stockrush.promotion.infra.kafka.KafkaEventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=promotion",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class PromotionCouponUsageEventHandlerIntegrationTest {

    private static final Instant ORDER_CREATED_AT = Instant.parse("2026-05-13T04:30:00Z");
    private static final UUID ORDER_CREATED_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7a21");
    private static final UUID ORDER_CONFIRMED_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7a22");
    private static final UUID ORDER_CANCELLED_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7a23");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PromotionCouponUsageEventHandler handler;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from coupon_usages").update();
        jdbcClient.sql("delete from admin_coupon_command_idempotency").update();
        jdbcClient.sql("delete from coupons").update();
        insertCoupon("WELCOME10");
    }

    @Test
    void reserves_and_consumes_coupon_usage_from_order_events() {
        handler.handleOrderCreated(orderCreatedWithCoupon("ord_coupon_001", ORDER_CREATED_EVENT_ID));
        handler.handleOrderCreated(orderCreatedWithCoupon("ord_coupon_001", ORDER_CREATED_EVENT_ID));

        assertEquals(1, queryInt("select count(*) from coupon_usages where order_id = 'ord_coupon_001'"));
        assertEquals("RESERVED", queryString("select status from coupon_usages where order_id = 'ord_coupon_001'"));
        assertEquals("WELCOME10", queryString("select coupon_code from coupon_usages where order_id = 'ord_coupon_001'"));
        assertEquals(new BigDecimal("5000.00"), queryBigDecimal("select discount_amount from coupon_usages where order_id = 'ord_coupon_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + ORDER_CREATED_EVENT_ID + "'"));

        handler.handleOrderConfirmed(orderConfirmed("ord_coupon_001"));
        handler.handleOrderConfirmed(orderConfirmed("ord_coupon_001"));

        assertEquals("CONSUMED", queryString("select status from coupon_usages where order_id = 'ord_coupon_001'"));
        assertEquals(2, queryInt("select count(*) from processed_events"));
    }

    @Test
    void releases_reserved_coupon_usage_when_order_is_cancelled() {
        handler.handleOrderCreated(orderCreatedWithCoupon("ord_coupon_002", ORDER_CREATED_EVENT_ID));

        handler.handleOrderCancelled(orderCancelled("ord_coupon_002"));

        assertEquals("RELEASED", queryString("select status from coupon_usages where order_id = 'ord_coupon_002'"));
        assertEquals(2, queryInt("select count(*) from processed_events"));
    }

    @Test
    void ignores_order_created_without_coupon_usage() {
        handler.handleOrderCreated(orderCreatedWithoutCoupon("ord_no_coupon_001"));

        assertEquals(0, queryInt("select count(*) from coupon_usages"));
        assertEquals(1, queryInt("select count(*) from processed_events"));
    }

    private KafkaEventEnvelope<OrderCreatedPayload> orderCreatedWithCoupon(String orderId, UUID eventId) {
        return new KafkaEventEnvelope<>(
            eventId,
            "OrderCreated",
            1,
            "order",
            orderId,
            "corr-" + orderId,
            null,
            "idem-" + orderId,
            ORDER_CREATED_AT,
            "order-service",
            new OrderCreatedPayload(
                orderId,
                "member-1",
                List.of(new OrderCreatedItemPayload("LIMITED-001", "SKU-001", 1, new BigDecimal("80000.00"))),
                new BigDecimal("80000.00"),
                "WELCOME10",
                new BigDecimal("5000.00"),
                new BigDecimal("75000.00"),
                ORDER_CREATED_AT
            )
        );
    }

    private KafkaEventEnvelope<OrderCreatedPayload> orderCreatedWithoutCoupon(String orderId) {
        return new KafkaEventEnvelope<>(
            ORDER_CREATED_EVENT_ID,
            "OrderCreated",
            1,
            "order",
            orderId,
            "corr-" + orderId,
            null,
            "idem-" + orderId,
            ORDER_CREATED_AT,
            "order-service",
            new OrderCreatedPayload(
                orderId,
                "member-1",
                List.of(new OrderCreatedItemPayload("LIMITED-001", "SKU-001", 1, new BigDecimal("12000.00"))),
                new BigDecimal("12000.00"),
                null,
                BigDecimal.ZERO,
                new BigDecimal("12000.00"),
                ORDER_CREATED_AT
            )
        );
    }

    private KafkaEventEnvelope<OrderConfirmedPayload> orderConfirmed(String orderId) {
        return new KafkaEventEnvelope<>(
            ORDER_CONFIRMED_EVENT_ID,
            "OrderConfirmed",
            1,
            "order",
            orderId,
            "corr-" + orderId,
            ORDER_CREATED_EVENT_ID,
            "idem-" + orderId,
            Instant.parse("2026-05-13T04:31:00Z"),
            "order-service",
            new OrderConfirmedPayload(orderId, Instant.parse("2026-05-13T04:31:00Z"))
        );
    }

    private KafkaEventEnvelope<OrderCancelledPayload> orderCancelled(String orderId) {
        return new KafkaEventEnvelope<>(
            ORDER_CANCELLED_EVENT_ID,
            "OrderCancelled",
            1,
            "order",
            orderId,
            "corr-" + orderId,
            ORDER_CREATED_EVENT_ID,
            "idem-" + orderId,
            Instant.parse("2026-05-13T04:32:00Z"),
            "order-service",
            new OrderCancelledPayload(orderId, "PAYMENT_DECLINED", Instant.parse("2026-05-13T04:32:00Z"))
        );
    }

    private void insertCoupon(String couponCode) {
        jdbcClient.sql("""
                insert into coupons (
                  coupon_code, name, discount_type, discount_value, min_order_amount,
                  max_discount_amount, status, starts_at, ends_at, created_at, updated_at
                )
                values (
                  :couponCode, 'Welcome 10%', 'PERCENTAGE', 10.00, 20000.00,
                  5000.00, 'ACTIVE', :startsAt, :endsAt, now(), now()
                )
                """)
            .param("couponCode", couponCode)
            .param("startsAt", OffsetDateTime.parse("2026-01-01T00:00:00Z"))
            .param("endsAt", OffsetDateTime.parse("2099-12-31T23:59:59Z"))
            .update();
    }

    private int queryInt(String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }

    private String queryString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }

    private BigDecimal queryBigDecimal(String sql) {
        return jdbcClient.sql(sql).query(BigDecimal.class).single();
    }
}
