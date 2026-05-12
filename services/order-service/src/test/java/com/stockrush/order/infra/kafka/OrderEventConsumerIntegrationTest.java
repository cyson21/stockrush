package com.stockrush.order.infra.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=orders",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class OrderEventConsumerIntegrationTest {

    @Autowired
    private OrderInventoryEventConsumer inventoryConsumer;

    @Autowired
    private OrderPaymentEventConsumer paymentConsumer;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("delete from outbox_events").update();
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from order_items").update();
        jdbcClient.sql("delete from customer_orders").update();
        jdbcClient.sql("""
                insert into customer_orders (
                  order_id, member_id, status, saga_status, total_amount, idempotency_key, created_at, updated_at
                )
                values (
                  'ord_consumer_001', 'member-1', 'CREATED', 'STARTED', 24000.00,
                  'idem-consumer-001', now(), now()
                )
                """)
            .update();
    }

    @Test
    void routes_inventory_reserved_event_to_saga_handler() {
        inventoryConsumer.consume("""
            {
              "eventId": "018f8d0b-8d32-7c42-9f1b-78328e0f7d01",
              "eventType": "InventoryReserved",
              "eventVersion": 1,
              "aggregateType": "inventory-reservation",
              "aggregateId": "ord_consumer_001",
              "correlationId": "corr-consumer-001",
              "causationId": null,
              "idempotencyKey": "idem-consumer-001",
              "occurredAt": "2026-05-12T16:00:00Z",
              "sourceService": "inventory-service",
              "payload": {
                "orderId": "ord_consumer_001",
                "items": [
                  {
                    "productCode": "LIMITED-001",
                    "skuId": "SKU-001",
                    "quantity": 2
                  }
                ],
                "reservedAt": "2026-05-12T16:00:00Z"
              }
            }
            """);

        assertEquals("PAYMENT_REQUESTED", queryString("""
            select saga_status from customer_orders where order_id = 'ord_consumer_001'
            """));
        assertEquals("PaymentAuthorizationRequested", queryString("select event_type from outbox_events"));
    }

    @Test
    void routes_payment_authorized_event_to_saga_handler() {
        paymentConsumer.consume("""
            {
              "eventId": "018f8d0b-8d32-7c42-9f1b-78328e0f7d02",
              "eventType": "PaymentAuthorized",
              "eventVersion": 1,
              "aggregateType": "payment",
              "aggregateId": "ord_consumer_001",
              "correlationId": "corr-consumer-001",
              "causationId": null,
              "idempotencyKey": "idem-consumer-001",
              "occurredAt": "2026-05-12T16:01:00Z",
              "sourceService": "payment-service",
              "payload": {
                "paymentId": "018f8d0b-8d32-7c42-9f1b-78328e0f7d03",
                "orderId": "ord_consumer_001",
                "amount": 24000.00,
                "method": "CARD",
                "authorizedAt": "2026-05-12T16:01:00Z"
              }
            }
            """);

        assertEquals("CONFIRMED", queryString("select status from customer_orders where order_id = 'ord_consumer_001'"));
        assertEquals("COMPLETED", queryString("""
            select saga_status from customer_orders where order_id = 'ord_consumer_001'
            """));
        assertEquals("OrderConfirmed", queryString("select event_type from outbox_events"));
    }

    private String queryString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }
}
