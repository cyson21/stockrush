package com.stockrush.inventory.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.stockrush.inventory.infra.kafka.KafkaEventEnvelope;
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
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=inventory",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush",
    "stockrush.kafka.listeners.enabled=false",
    "spring.kafka.listener.auto-startup=false"
})
class InventoryReservationHandlerIntegrationTest {

    private static final UUID ORDER_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7b01");
    private static final UUID ORDER_CONFIRMED_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7b02");
    private static final UUID ORDER_CANCELLED_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7b03");
    private static final UUID ORDER_OVER_REQUESTED_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7b04");

    @Autowired
    private InventoryReservationHandler handler;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("delete from outbox_events").update();
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from stock_reservations").update();
        jdbcClient.sql("delete from stock_items").update();
        jdbcClient.sql("""
                insert into stock_items (sku_id, product_code, available_quantity, reserved_quantity, created_at, updated_at)
                values ('SKU-001', 'LIMITED-001', 5, 0, now(), now())
                """)
            .update();
    }

    @Test
    void reserves_stock_and_writes_outbox_when_order_created() {
        handler.handle(orderCreated());

        assertEquals(3, queryInt("select available_quantity from stock_items where sku_id = 'SKU-001'"));
        assertEquals(2, queryInt("select reserved_quantity from stock_items where sku_id = 'SKU-001'"));
        assertEquals(1, queryInt("select count(*) from stock_reservations where order_id = 'ord_inventory_001'"));
        assertEquals("RESERVED", queryString("select status from stock_reservations where order_id = 'ord_inventory_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + ORDER_EVENT_ID + "'"));
        assertEquals("InventoryReserved", queryString("select event_type from outbox_events"));
        assertEquals("PENDING", queryString("select status from outbox_events"));
    }

    @Test
    void fails_reservation_when_same_sku_items_exceed_available_stock_in_total() {
        handler.handle(orderCreatedWithItems(
            "ord_inventory_over_requested_001",
            ORDER_OVER_REQUESTED_EVENT_ID,
            List.of(
                new OrderCreatedItemPayload("LIMITED-001", "SKU-001", 4, new BigDecimal("12000.00")),
                new OrderCreatedItemPayload("LIMITED-001", "SKU-001", 4, new BigDecimal("12000.00"))
            ),
            new BigDecimal("96000.00")
        ));

        assertEquals(5, queryInt("select available_quantity from stock_items where sku_id = 'SKU-001'"));
        assertEquals(0, queryInt("select reserved_quantity from stock_items where sku_id = 'SKU-001'"));
        assertEquals(0, queryInt("select count(*) from stock_reservations where order_id = 'ord_inventory_over_requested_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + ORDER_OVER_REQUESTED_EVENT_ID + "'"));
        assertEquals("InventoryReservationFailed", queryString("select event_type from outbox_events"));
        assertEquals("INSUFFICIENT_STOCK", queryString("select payload ->> 'reason' from outbox_events"));
    }

    @Test
    void confirms_reserved_stock_and_writes_outbox_when_order_confirmed() {
        insertReservedStock("ord_inventory_confirmed_001", 3, 2);

        handler.handleOrderConfirmed(orderConfirmed());

        assertEquals(3, queryInt("select available_quantity from stock_items where sku_id = 'SKU-001'"));
        assertEquals(0, queryInt("select reserved_quantity from stock_items where sku_id = 'SKU-001'"));
        assertEquals("CONFIRMED", queryString("select status from stock_reservations where order_id = 'ord_inventory_confirmed_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + ORDER_CONFIRMED_EVENT_ID + "'"));
        assertEquals("InventoryReservationConfirmed", queryString("select event_type from outbox_events"));
        assertEquals("ord_inventory_confirmed_001", queryString("select payload ->> 'orderId' from outbox_events"));
        assertEquals("SKU-001", queryString("select payload #>> '{items,0,skuId}' from outbox_events"));
    }

    @Test
    void releases_reserved_stock_and_writes_outbox_when_order_cancelled() {
        insertReservedStock("ord_inventory_cancelled_001", 3, 2);

        handler.handleOrderCancelled(orderCancelled());

        assertEquals(5, queryInt("select available_quantity from stock_items where sku_id = 'SKU-001'"));
        assertEquals(0, queryInt("select reserved_quantity from stock_items where sku_id = 'SKU-001'"));
        assertEquals("RELEASED", queryString("select status from stock_reservations where order_id = 'ord_inventory_cancelled_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + ORDER_CANCELLED_EVENT_ID + "'"));
        assertEquals("InventoryReservationReleased", queryString("select event_type from outbox_events"));
        assertEquals("ord_inventory_cancelled_001", queryString("select payload ->> 'orderId' from outbox_events"));
        assertEquals("PAYMENT_DECLINED", queryString("select payload ->> 'reason' from outbox_events"));
    }

    @Test
    void ignores_duplicate_order_cancelled_event() {
        insertReservedStock("ord_inventory_cancelled_001", 3, 2);
        KafkaEventEnvelope<OrderCancelledPayload> event = orderCancelled();

        handler.handleOrderCancelled(event);
        handler.handleOrderCancelled(event);

        assertEquals(5, queryInt("select available_quantity from stock_items where sku_id = 'SKU-001'"));
        assertEquals(0, queryInt("select reserved_quantity from stock_items where sku_id = 'SKU-001'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + ORDER_CANCELLED_EVENT_ID + "'"));
        assertEquals(1, queryInt("select count(*) from outbox_events"));
    }

    private KafkaEventEnvelope<OrderCreatedPayload> orderCreated() {
        return orderCreatedWithItems(
            "ord_inventory_001",
            ORDER_EVENT_ID,
            List.of(new OrderCreatedItemPayload("LIMITED-001", "SKU-001", 2, new BigDecimal("12000.00"))),
            new BigDecimal("24000.00")
        );
    }

    private KafkaEventEnvelope<OrderCreatedPayload> orderCreatedWithItems(
        String orderId,
        UUID eventId,
        List<OrderCreatedItemPayload> items,
        BigDecimal totalAmount
    ) {
        return new KafkaEventEnvelope<>(
            eventId,
            "OrderCreated",
            1,
            "order",
            orderId,
            "corr-" + orderId,
            null,
            "idem-" + orderId,
            Instant.parse("2026-05-12T15:00:00Z"),
            "order-service",
            new OrderCreatedPayload(
                orderId,
                "member-1",
                items,
                totalAmount,
                Instant.parse("2026-05-12T15:00:00Z")
            )
        );
    }

    private KafkaEventEnvelope<OrderConfirmedPayload> orderConfirmed() {
        return new KafkaEventEnvelope<>(
            ORDER_CONFIRMED_EVENT_ID,
            "OrderConfirmed",
            1,
            "order",
            "ord_inventory_confirmed_001",
            "corr-inventory-confirmed-001",
            ORDER_EVENT_ID,
            "idem-inventory-confirmed-001",
            Instant.parse("2026-05-12T16:00:00Z"),
            "order-service",
            new OrderConfirmedPayload(
                "ord_inventory_confirmed_001",
                Instant.parse("2026-05-12T16:00:00Z")
            )
        );
    }

    private KafkaEventEnvelope<OrderCancelledPayload> orderCancelled() {
        return new KafkaEventEnvelope<>(
            ORDER_CANCELLED_EVENT_ID,
            "OrderCancelled",
            1,
            "order",
            "ord_inventory_cancelled_001",
            "corr-inventory-cancelled-001",
            ORDER_EVENT_ID,
            "idem-inventory-cancelled-001",
            Instant.parse("2026-05-12T16:00:00Z"),
            "order-service",
            new OrderCancelledPayload(
                "ord_inventory_cancelled_001",
                "PAYMENT_DECLINED",
                Instant.parse("2026-05-12T16:00:00Z")
            )
        );
    }

    private void insertReservedStock(String orderId, int availableQuantity, int reservedQuantity) {
        jdbcClient.sql("""
                update stock_items
                set available_quantity = :availableQuantity,
                    reserved_quantity = :reservedQuantity
                where sku_id = 'SKU-001'
                """)
            .param("availableQuantity", availableQuantity)
            .param("reservedQuantity", reservedQuantity)
            .update();
        jdbcClient.sql("""
                insert into stock_reservations (
                  reservation_id, order_id, sku_id, quantity, status, expires_at, created_at, updated_at
                )
                values (:reservationId, :orderId, 'SKU-001', :reservedQuantity, 'RESERVED', now() + interval '15 minutes', now(), now())
                """)
            .param("reservationId", UUID.randomUUID())
            .param("orderId", orderId)
            .param("reservedQuantity", reservedQuantity)
            .update();
    }

    private int queryInt(String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }

    private String queryString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }
}
