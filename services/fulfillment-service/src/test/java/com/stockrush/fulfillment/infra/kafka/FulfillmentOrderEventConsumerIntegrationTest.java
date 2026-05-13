package com.stockrush.fulfillment.infra.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.stockrush.fulfillment.application.FulfillmentOrderEventHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=fulfillment",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush",
    "stockrush.kafka.listeners.enabled=false",
    "spring.kafka.listener.auto-startup=false"
})
class FulfillmentOrderEventConsumerIntegrationTest {

    private FulfillmentOrderEventConsumer consumer;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FulfillmentOrderEventHandler handler;

    @BeforeEach
    void setUp() {
        consumer = new FulfillmentOrderEventConsumer(objectMapper, handler);
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from fulfillment_requests").update();
    }

    @Test
    void dispatches_order_confirmed_json_to_prepare_fulfillment() {
        consumer.consume(orderConfirmedJson());

        assertEquals("PREPARING", queryString("select status from fulfillment_requests where order_id = 'ord_fulfill_json_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events"));
    }

    @Test
    void ignores_non_fulfillment_order_events() {
        consumer.consume(orderCancelledJson());

        assertEquals(0, queryInt("select count(*) from fulfillment_requests"));
        assertEquals(0, queryInt("select count(*) from processed_events"));
    }

    private String orderConfirmedJson() {
        return """
            {
              "eventId": "018f8d0b-8d32-7c42-9f1b-78328e0f7b11",
              "eventType": "OrderConfirmed",
              "eventVersion": 1,
              "aggregateType": "order",
              "aggregateId": "ord_fulfill_json_001",
              "correlationId": "corr-ord_fulfill_json_001",
              "causationId": null,
              "idempotencyKey": "idem-ord_fulfill_json_001",
              "occurredAt": "2026-05-13T08:15:00Z",
              "sourceService": "order-service",
              "payload": {
                "orderId": "ord_fulfill_json_001",
                "confirmedAt": "2026-05-13T08:15:00Z"
              }
            }
            """;
    }

    private String orderCancelledJson() {
        return """
            {
              "eventId": "018f8d0b-8d32-7c42-9f1b-78328e0f7b12",
              "eventType": "OrderCancelled",
              "eventVersion": 1,
              "aggregateType": "order",
              "aggregateId": "ord_fulfill_json_002",
              "correlationId": "corr-ord_fulfill_json_002",
              "causationId": null,
              "idempotencyKey": "idem-ord_fulfill_json_002",
              "occurredAt": "2026-05-13T08:16:00Z",
              "sourceService": "order-service",
              "payload": {
                "orderId": "ord_fulfill_json_002",
                "reason": "PAYMENT_DECLINED",
                "cancelledAt": "2026-05-13T08:16:00Z"
              }
            }
            """;
    }

    private int queryInt(String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }

    private String queryString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }
}
