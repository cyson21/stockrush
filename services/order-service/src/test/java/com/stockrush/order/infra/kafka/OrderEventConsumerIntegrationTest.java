package com.stockrush.order.infra.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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

    @Test
    void routes_payment_authorization_delayed_event_to_saga_handler() {
        paymentConsumer.consume("""
            {
              "eventId": "018f8d0b-8d32-7c42-9f1b-78328e0f7d04",
              "eventType": "PaymentAuthorizationDelayed",
              "eventVersion": 1,
              "aggregateType": "payment",
              "aggregateId": "ord_consumer_001",
              "correlationId": "corr-consumer-001",
              "causationId": null,
              "idempotencyKey": "idem-consumer-001",
              "occurredAt": "2026-05-12T16:01:00Z",
              "sourceService": "payment-service",
              "payload": {
                "orderId": "ord_consumer_001",
                "amount": 24000.00,
                "method": "DELAY_CARD",
                "reason": "PAYMENT_DELAYED",
                "delayedAt": "2026-05-12T16:01:00Z"
              }
            }
            """);

        assertEquals("CREATED", queryString("select status from customer_orders where order_id = 'ord_consumer_001'"));
        assertEquals("PAYMENT_DELAYED", queryString("""
            select saga_status from customer_orders where order_id = 'ord_consumer_001'
            """));
        assertEquals(0, queryInt("select count(*) from outbox_events"));
    }

    @Test
    void routes_payment_canceled_event_to_saga_handler() {
        jdbcClient.sql("""
                update customer_orders
                set saga_status = 'PAYMENT_CANCEL_REQUESTED'
                where order_id = 'ord_consumer_001'
                """)
            .update();

        paymentConsumer.consume("""
            {
              "eventId": "018f8d0b-8d32-7c42-9f1b-78328e0f7d05",
              "eventType": "PaymentCanceled",
              "eventVersion": 1,
              "aggregateType": "payment",
              "aggregateId": "ord_consumer_001",
              "correlationId": "corr-consumer-001",
              "causationId": null,
              "idempotencyKey": "idem-consumer-001",
              "occurredAt": "2026-05-12T16:02:00Z",
              "sourceService": "payment-service",
              "payload": {
                "orderId": "ord_consumer_001",
                "amount": 24000.00,
                "method": "DELAY_CARD",
                "reason": "PAYMENT_CANCELED",
                "canceledAt": "2026-05-12T16:02:00Z"
              }
            }
            """);

        assertEquals("CANCELLED", queryString("select status from customer_orders where order_id = 'ord_consumer_001'"));
        assertEquals("FAILED", queryString("""
            select saga_status from customer_orders where order_id = 'ord_consumer_001'
            """));
        assertEquals("OrderCancelled", queryString("select event_type from outbox_events"));
        assertEquals("PAYMENT_CANCELED", queryString("select payload ->> 'reason' from outbox_events"));
    }

    @Test
    void ignores_inventory_events_that_are_not_order_saga_inputs() {
        assertDoesNotThrow(() -> inventoryConsumer.consume("""
            {
              "eventId": "018f8d0b-8d32-7c42-9f1b-78328e0f7d06",
              "eventType": "InventoryReservationConfirmed",
              "eventVersion": 1,
              "aggregateType": "inventory-reservation",
              "aggregateId": "ord_consumer_001",
              "correlationId": "corr-consumer-001",
              "causationId": null,
              "idempotencyKey": "idem-consumer-001",
              "occurredAt": "2026-05-12T16:03:00Z",
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
                "confirmedAt": "2026-05-12T16:03:00Z"
              }
            }
            """));

        assertEquals("STARTED", queryString("""
            select saga_status from customer_orders where order_id = 'ord_consumer_001'
            """));
        assertEquals(0, queryInt("select count(*) from outbox_events"));
        assertEquals(0, queryInt("select count(*) from processed_events"));
    }

    @Test
    void ignores_payment_events_that_are_not_order_saga_inputs() {
        assertDoesNotThrow(() -> paymentConsumer.consume("""
            {
              "eventId": "018f8d0b-8d32-7c42-9f1b-78328e0f7d07",
              "eventType": "PaymentAuditRecorded",
              "eventVersion": 1,
              "aggregateType": "payment",
              "aggregateId": "ord_consumer_001",
              "correlationId": "corr-consumer-001",
              "causationId": null,
              "idempotencyKey": "idem-consumer-001",
              "occurredAt": "2026-05-12T16:04:00Z",
              "sourceService": "payment-service",
              "payload": {
                "orderId": "ord_consumer_001"
              }
            }
            """));

        assertEquals("STARTED", queryString("""
            select saga_status from customer_orders where order_id = 'ord_consumer_001'
            """));
        assertEquals(0, queryInt("select count(*) from outbox_events"));
        assertEquals(0, queryInt("select count(*) from processed_events"));
    }

    private int queryInt(String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }

    private String queryString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }
}
