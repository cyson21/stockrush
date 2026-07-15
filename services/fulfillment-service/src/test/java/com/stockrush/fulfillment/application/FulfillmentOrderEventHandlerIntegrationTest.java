
package com.stockrush.fulfillment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.stockrush.fulfillment.infra.kafka.KafkaEventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=fulfillment",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class FulfillmentOrderEventHandlerIntegrationTest {

    private static final UUID ORDER_CONFIRMED_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7b01");
    private static final UUID ORDER_CONFIRMED_REPLAY_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7b02");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-05-13T08:10:00Z");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private FulfillmentOrderEventHandler handler;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from fulfillment_requests").update();
    }

    @Test
    void creates_preparing_fulfillment_request_when_order_is_confirmed() {
        handler.handleOrderConfirmed(orderConfirmed("ord_fulfill_001", ORDER_CONFIRMED_EVENT_ID));
        handler.handleOrderConfirmed(orderConfirmed("ord_fulfill_001", ORDER_CONFIRMED_EVENT_ID));

        assertEquals(1, queryInt("select count(*) from fulfillment_requests where order_id = 'ord_fulfill_001'"));
        assertEquals("PREPARING", queryString("select status from fulfillment_requests where order_id = 'ord_fulfill_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events"));
    }

    @Test
    void keeps_one_fulfillment_request_when_same_order_is_confirmed_by_later_event() {
        handler.handleOrderConfirmed(orderConfirmed("ord_fulfill_002", ORDER_CONFIRMED_EVENT_ID));
        handler.handleOrderConfirmed(orderConfirmed("ord_fulfill_002", ORDER_CONFIRMED_REPLAY_EVENT_ID));

        assertEquals(1, queryInt("select count(*) from fulfillment_requests where order_id = 'ord_fulfill_002'"));
        assertEquals(2, queryInt("select count(*) from processed_events"));
    }

    @Test
    void does_not_mark_invalid_order_confirmed_event_as_processed() {
        assertThrows(
            IllegalArgumentException.class,
            () -> handler.handleOrderConfirmed(orderConfirmed("", ORDER_CONFIRMED_EVENT_ID))
        );

        assertEquals(0, queryInt("select count(*) from fulfillment_requests"));
        assertEquals(0, queryInt("select count(*) from processed_events"));
    }

    private KafkaEventEnvelope<OrderConfirmedPayload> orderConfirmed(String orderId, UUID eventId) {
        return new KafkaEventEnvelope<>(
            eventId,
            "OrderConfirmed",
            1,
            "order",
            orderId,
            "corr-" + orderId,
            null,
            "idem-" + orderId,
            CONFIRMED_AT,
            "order-service",
            new OrderConfirmedPayload(orderId, CONFIRMED_AT)
        );
    }

    private int queryInt(String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }

    private String queryString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }
}
