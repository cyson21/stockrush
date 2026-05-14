package com.stockrush.readmodel.infra.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.stockrush.readmodel.application.OrderReadModelProjectionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=read_model",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush",
    "stockrush.kafka.listeners.enabled=false",
    "spring.kafka.listener.auto-startup=false"
})
class ReadModelOrderEventConsumerIntegrationTest {

    private ReadModelOrderEventConsumer consumer;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderReadModelProjectionHandler handler;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        consumer = new ReadModelOrderEventConsumer(objectMapper, handler);
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from order_summaries").update();
    }

    @Test
    void dispatches_order_lifecycle_json_to_projection_handler() {
        consumer.consume(orderCreatedJson());
        consumer.consume(orderCancelledJson());

        assertEquals("CANCELLED", queryString("select status from order_summaries where order_id = 'ord_read_json_001'"));
        assertEquals("FAILED", queryString("select saga_status from order_summaries where order_id = 'ord_read_json_001'"));
        assertEquals("PAYMENT_DECLINED", queryString("select cancellation_reason from order_summaries where order_id = 'ord_read_json_001'"));
        assertEquals(2, queryInt("select count(*) from processed_events"));
    }

    private String orderCreatedJson() {
        return """
            {
              "eventId": "018f8d0b-8d32-7c42-9f1b-78328e0f8b01",
              "eventType": "OrderCreated",
              "eventVersion": 1,
              "aggregateType": "order",
              "aggregateId": "ord_read_json_001",
              "correlationId": "corr-ord_read_json_001",
              "causationId": null,
              "idempotencyKey": "idem-ord_read_json_001",
              "occurredAt": "2026-05-14T00:20:00Z",
              "sourceService": "order-service",
              "payload": {
                "orderId": "ord_read_json_001",
                "memberId": "member-read-json",
                "items": [
                  {
                    "productCode": "LIMITED-001",
                    "skuId": "SKU-001",
                    "quantity": 1,
                    "unitPrice": 30000.00
                  }
                ],
                "totalAmount": 30000.00,
                "couponCode": null,
                "discountAmount": 0.00,
                "payableAmount": 30000.00,
                "createdAt": "2026-05-14T00:20:00Z"
              }
            }
            """;
    }

    private String orderCancelledJson() {
        return """
            {
              "eventId": "018f8d0b-8d32-7c42-9f1b-78328e0f8b02",
              "eventType": "OrderCancelled",
              "eventVersion": 1,
              "aggregateType": "order",
              "aggregateId": "ord_read_json_001",
              "correlationId": "corr-ord_read_json_001",
              "causationId": "018f8d0b-8d32-7c42-9f1b-78328e0f8b01",
              "idempotencyKey": "idem-ord_read_json_001",
              "occurredAt": "2026-05-14T00:21:00Z",
              "sourceService": "order-service",
              "payload": {
                "orderId": "ord_read_json_001",
                "reason": "PAYMENT_DECLINED",
                "cancelledAt": "2026-05-14T00:21:00Z"
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
