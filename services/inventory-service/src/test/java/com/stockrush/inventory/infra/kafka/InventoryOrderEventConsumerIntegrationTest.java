package com.stockrush.inventory.infra.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.stockrush.inventory.application.InventoryReservationHandler;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=inventory",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush",
    "stockrush.kafka.listeners.enabled=false",
    "spring.kafka.listener.auto-startup=false"
})
class InventoryOrderEventConsumerIntegrationTest {

    private static final UUID ORDER_CONFIRMED_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7b10");
    private static final UUID ORDER_CANCELLED_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7b11");
    private static final UUID ORDER_CREATED_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7b12");
    private static final String ORDER_ID = "ord_inventory_consumer_001";
    private static final String SKU_ID = "SKU-CONSUMER-001";

    private InventoryOrderEventConsumer consumer;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryReservationHandler handler;

    @BeforeEach
    void setUp() {
        consumer = new InventoryOrderEventConsumer(objectMapper, handler);
        jdbcClient.sql("delete from outbox_events").update();
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from stock_reservations").update();
        jdbcClient.sql("delete from stock_items").update();
        jdbcClient.sql("""
                insert into stock_items (sku_id, product_code, available_quantity, reserved_quantity, created_at, updated_at)
                values (:skuId, 'LIMITED-CONSUMER-001', 3, 2, now(), now())
                """)
            .param("skuId", SKU_ID)
            .update();
        jdbcClient.sql("""
                insert into stock_reservations (
                  reservation_id, order_id, sku_id, quantity, status, expires_at, created_at, updated_at
                )
                values (:reservationId, :orderId, :skuId, 2, 'RESERVED', now() + interval '15 minutes', now(), now())
                """)
            .param("reservationId", UUID.randomUUID())
            .param("orderId", ORDER_ID)
            .param("skuId", SKU_ID)
            .update();
    }

    @Test
    void dispatches_order_created_json_with_coupon_fields_to_reserve_inventory() {
        consumer.consume(orderCreatedJson());

        assertEquals(2, queryInt("select available_quantity from stock_items where sku_id = '" + SKU_ID + "'"));
        assertEquals(3, queryInt("select reserved_quantity from stock_items where sku_id = '" + SKU_ID + "'"));
        assertEquals(1, queryInt("select count(*) from stock_reservations where order_id = 'ord_inventory_consumer_created_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + ORDER_CREATED_EVENT_ID + "'"));
        assertEquals("InventoryReserved", queryString("select event_type from outbox_events"));
    }

    @Test
    void dispatches_order_confirmed_json_to_confirm_inventory_reservation() {
        consumer.consume(orderConfirmedJson());

        assertEquals(3, queryInt("select available_quantity from stock_items where sku_id = '" + SKU_ID + "'"));
        assertEquals(0, queryInt("select reserved_quantity from stock_items where sku_id = '" + SKU_ID + "'"));
        assertEquals("CONFIRMED", queryString("select status from stock_reservations where order_id = '" + ORDER_ID + "'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + ORDER_CONFIRMED_EVENT_ID + "'"));
        assertEquals(1, queryInt("select count(*) from outbox_events"));
        assertEquals("InventoryReservationConfirmed", queryString("select event_type from outbox_events"));
    }

    @Test
    void dispatches_order_cancelled_json_to_release_inventory_reservation() {
        consumer.consume(orderCancelledJson());

        assertEquals(5, queryInt("select available_quantity from stock_items where sku_id = '" + SKU_ID + "'"));
        assertEquals(0, queryInt("select reserved_quantity from stock_items where sku_id = '" + SKU_ID + "'"));
        assertEquals("RELEASED", queryString("select status from stock_reservations where order_id = '" + ORDER_ID + "'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + ORDER_CANCELLED_EVENT_ID + "'"));
        assertEquals(1, queryInt("select count(*) from outbox_events"));
        assertEquals("InventoryReservationReleased", queryString("select event_type from outbox_events"));
    }

    private String orderCreatedJson() {
        return """
            {
              "eventId": "%s",
              "eventType": "OrderCreated",
              "eventVersion": 1,
              "aggregateType": "order",
              "aggregateId": "ord_inventory_consumer_created_001",
              "correlationId": "corr-inventory-consumer-created",
              "causationId": null,
              "idempotencyKey": "idem-inventory-consumer-created",
              "occurredAt": "2026-05-12T17:00:00Z",
              "sourceService": "order-service",
              "payload": {
                "orderId": "ord_inventory_consumer_created_001",
                "memberId": "member-1",
                "items": [
                  {
                    "productCode": "LIMITED-CONSUMER-001",
                    "skuId": "%s",
                    "quantity": 1,
                    "unitPrice": 80000.00
                  }
                ],
                "totalAmount": 80000.00,
                "couponCode": "WELCOME10",
                "discountAmount": 5000.00,
                "payableAmount": 75000.00,
                "createdAt": "2026-05-12T17:00:00Z"
              }
            }
            """.formatted(ORDER_CREATED_EVENT_ID, SKU_ID);
    }

    private String orderConfirmedJson() {
        return """
            {
              "eventId": "%s",
              "eventType": "OrderConfirmed",
              "eventVersion": 1,
              "aggregateType": "order",
              "aggregateId": "%s",
              "correlationId": "corr-inventory-consumer-001",
              "causationId": null,
              "idempotencyKey": "idem-inventory-consumer-confirmed",
              "occurredAt": "2026-05-12T17:00:00Z",
              "sourceService": "order-service",
              "payload": {
                "orderId": "%s",
                "confirmedAt": "2026-05-12T17:00:00Z"
              }
            }
            """.formatted(ORDER_CONFIRMED_EVENT_ID, ORDER_ID, ORDER_ID);
    }

    private String orderCancelledJson() {
        return """
            {
              "eventId": "%s",
              "eventType": "OrderCancelled",
              "eventVersion": 1,
              "aggregateType": "order",
              "aggregateId": "%s",
              "correlationId": "corr-inventory-consumer-001",
              "causationId": null,
              "idempotencyKey": "idem-inventory-consumer-001",
              "occurredAt": "2026-05-12T17:00:00Z",
              "sourceService": "order-service",
              "payload": {
                "orderId": "%s",
                "reason": "PAYMENT_DECLINED",
                "cancelledAt": "2026-05-12T17:00:00Z"
              }
            }
            """.formatted(ORDER_CANCELLED_EVENT_ID, ORDER_ID, ORDER_ID);
    }

    private int queryInt(String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }

    private String queryString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }
}
