// OrderReadModelProjectionHandlerIntegrationTest: 이벤트/메시지 처리 흐름을 수신하고 도메인 상태 반영을 담당합니다.

package com.stockrush.readmodel.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.stockrush.readmodel.infra.kafka.KafkaEventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
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
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=read_model",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class OrderReadModelProjectionHandlerIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-05-14T00:10:00Z");
    private static final UUID ORDER_CREATED_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f8a01");
    private static final UUID LATE_ORDER_CREATED_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f8a04");
    private static final UUID ORDER_CONFIRMED_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f8a02");
    private static final UUID ORDER_CANCELLED_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f8a03");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OrderReadModelProjectionHandler handler;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from order_summaries").update();
    }

    @Test
    void projects_created_and_confirmed_order_summary() {
        handler.handleOrderCreated(orderCreated("ord_read_001", ORDER_CREATED_EVENT_ID));
        handler.handleOrderCreated(orderCreated("ord_read_001", ORDER_CREATED_EVENT_ID));

        assertEquals(1, queryInt("select count(*) from order_summaries where order_id = 'ord_read_001'"));
        assertEquals("CREATED", queryString("select status from order_summaries where order_id = 'ord_read_001'"));
        assertEquals("STARTED", queryString("select saga_status from order_summaries where order_id = 'ord_read_001'"));
        assertEquals("WELCOME10", queryString("select coupon_code from order_summaries where order_id = 'ord_read_001'"));
        assertEquals(new BigDecimal("5000.00"), queryBigDecimal("select discount_amount from order_summaries where order_id = 'ord_read_001'"));
        assertEquals(2, queryInt("select item_count from order_summaries where order_id = 'ord_read_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events"));

        handler.handleOrderConfirmed(orderConfirmed("ord_read_001"));
        handler.handleOrderConfirmed(orderConfirmed("ord_read_001"));

        assertEquals("CONFIRMED", queryString("select status from order_summaries where order_id = 'ord_read_001'"));
        assertEquals("COMPLETED", queryString("select saga_status from order_summaries where order_id = 'ord_read_001'"));
        assertEquals(2, queryInt("select count(*) from processed_events"));
    }

    @Test
    void projects_cancelled_order_summary() {
        handler.handleOrderCreated(orderCreated("ord_read_002", ORDER_CREATED_EVENT_ID));

        handler.handleOrderCancelled(orderCancelled("ord_read_002"));

        assertEquals("CANCELLED", queryString("select status from order_summaries where order_id = 'ord_read_002'"));
        assertEquals("FAILED", queryString("select saga_status from order_summaries where order_id = 'ord_read_002'"));
        assertEquals("PAYMENT_DECLINED", queryString("select cancellation_reason from order_summaries where order_id = 'ord_read_002'"));
        assertEquals(2, queryInt("select count(*) from processed_events"));
    }

    @Test
    void late_order_created_event_does_not_downgrade_terminal_summary() {
        handler.handleOrderCreated(orderCreated("ord_read_003", ORDER_CREATED_EVENT_ID));
        handler.handleOrderConfirmed(orderConfirmed("ord_read_003"));

        handler.handleOrderCreated(orderCreated("ord_read_003", LATE_ORDER_CREATED_EVENT_ID));

        assertEquals("CONFIRMED", queryString("select status from order_summaries where order_id = 'ord_read_003'"));
        assertEquals("COMPLETED", queryString("select saga_status from order_summaries where order_id = 'ord_read_003'"));
        assertEquals(3, queryInt("select count(*) from processed_events"));
    }

    @Test
    void confirmed_event_without_summary_rolls_back_processed_marker_for_retry() {
        assertThrows(IllegalStateException.class, () -> handler.handleOrderConfirmed(orderConfirmed("ord_read_missing_confirmed")));

        assertEquals(0, queryInt("select count(*) from processed_events"));

        handler.handleOrderCreated(orderCreated("ord_read_missing_confirmed", ORDER_CREATED_EVENT_ID));
        handler.handleOrderConfirmed(orderConfirmed("ord_read_missing_confirmed"));

        assertEquals("CONFIRMED", queryString("select status from order_summaries where order_id = 'ord_read_missing_confirmed'"));
        assertEquals(2, queryInt("select count(*) from processed_events"));
    }

    @Test
    void cancelled_event_without_summary_rolls_back_processed_marker_for_retry() {
        assertThrows(IllegalStateException.class, () -> handler.handleOrderCancelled(orderCancelled("ord_read_missing_cancelled")));

        assertEquals(0, queryInt("select count(*) from processed_events"));

        handler.handleOrderCreated(orderCreated("ord_read_missing_cancelled", ORDER_CREATED_EVENT_ID));
        handler.handleOrderCancelled(orderCancelled("ord_read_missing_cancelled"));

        assertEquals("CANCELLED", queryString("select status from order_summaries where order_id = 'ord_read_missing_cancelled'"));
        assertEquals(2, queryInt("select count(*) from processed_events"));
    }

    private KafkaEventEnvelope<OrderCreatedPayload> orderCreated(String orderId, UUID eventId) {
        return new KafkaEventEnvelope<>(
            eventId,
            "OrderCreated",
            1,
            "order",
            orderId,
            "corr-" + orderId,
            null,
            "idem-" + orderId,
            CREATED_AT,
            "order-service",
            new OrderCreatedPayload(
                orderId,
                "member-read-1",
                List.of(
                    new OrderCreatedItemPayload("LIMITED-001", "SKU-001", 1, new BigDecimal("50000.00")),
                    new OrderCreatedItemPayload("LIMITED-002", "SKU-002", 2, new BigDecimal("15000.00"))
                ),
                new BigDecimal("80000.00"),
                "WELCOME10",
                new BigDecimal("5000.00"),
                new BigDecimal("75000.00"),
                CREATED_AT
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
            Instant.parse("2026-05-14T00:12:00Z"),
            "order-service",
            new OrderConfirmedPayload(orderId, Instant.parse("2026-05-14T00:12:00Z"))
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
            Instant.parse("2026-05-14T00:13:00Z"),
            "order-service",
            new OrderCancelledPayload(orderId, "PAYMENT_DECLINED", Instant.parse("2026-05-14T00:13:00Z"))
        );
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
