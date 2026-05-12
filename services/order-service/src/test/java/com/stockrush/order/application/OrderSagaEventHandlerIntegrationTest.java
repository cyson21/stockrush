package com.stockrush.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.stockrush.order.infra.kafka.KafkaEventEnvelope;
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
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=orders",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class OrderSagaEventHandlerIntegrationTest {

    private static final UUID INVENTORY_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7c01");
    private static final UUID PAYMENT_EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7c02");

    @Autowired
    private OrderSagaEventHandler handler;

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
                  order_id, member_id, status, saga_status, total_amount, payment_method,
                  idempotency_key, created_at, updated_at
                )
                values (
                  'ord_saga_001', 'member-1', 'CREATED', 'STARTED', 24000.00, 'FAIL_CARD',
                  'idem-saga-001', now(), now()
                )
                """)
            .update();
    }

    @Test
    void writes_payment_authorization_request_with_order_payment_method_when_inventory_reserved() {
        handler.handleInventoryReserved(inventoryReserved());

        assertEquals("PAYMENT_REQUESTED", queryString("""
            select saga_status from customer_orders where order_id = 'ord_saga_001'
            """));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + INVENTORY_EVENT_ID + "'"));
        assertEquals("PaymentAuthorizationRequested", queryString("select event_type from outbox_events"));
        assertEquals("stockrush.payment.commands.v1", queryString("select topic from outbox_events"));
        assertEquals("PENDING", queryString("select status from outbox_events"));
        assertEquals("ord_saga_001", queryString("select payload ->> 'orderId' from outbox_events"));
        assertEquals("24000.00", queryString("select payload ->> 'amount' from outbox_events"));
        assertEquals("FAIL_CARD", queryString("select payload ->> 'method' from outbox_events"));
        assertEquals(INVENTORY_EVENT_ID.toString(), queryString("select headers ->> 'causationId' from outbox_events"));
    }

    @Test
    void confirms_order_when_payment_authorized() {
        handler.handlePaymentAuthorized(paymentAuthorized());

        assertEquals("CONFIRMED", queryString("select status from customer_orders where order_id = 'ord_saga_001'"));
        assertEquals("COMPLETED", queryString("select saga_status from customer_orders where order_id = 'ord_saga_001'"));
        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + PAYMENT_EVENT_ID + "'"));
        assertEquals("OrderConfirmed", queryString("select event_type from outbox_events"));
        assertEquals("stockrush.order.events.v1", queryString("select topic from outbox_events"));
        assertEquals("PENDING", queryString("select status from outbox_events"));
    }

    @Test
    void cancels_order_when_inventory_reservation_failed() {
        handler.handleInventoryReservationFailed(inventoryReservationFailed());

        assertEquals("CANCELLED", queryString("select status from customer_orders where order_id = 'ord_saga_001'"));
        assertEquals("FAILED", queryString("select saga_status from customer_orders where order_id = 'ord_saga_001'"));
        assertEquals("OrderCancelled", queryString("select event_type from outbox_events"));
        assertEquals("INSUFFICIENT_STOCK", queryString("select payload ->> 'reason' from outbox_events"));
    }

    @Test
    void cancels_order_when_payment_authorization_failed() {
        handler.handlePaymentAuthorizationFailed(paymentAuthorizationFailed());

        assertEquals("CANCELLED", queryString("select status from customer_orders where order_id = 'ord_saga_001'"));
        assertEquals("FAILED", queryString("select saga_status from customer_orders where order_id = 'ord_saga_001'"));
        assertEquals("OrderCancelled", queryString("select event_type from outbox_events"));
        assertEquals("PAYMENT_DECLINED", queryString("select payload ->> 'reason' from outbox_events"));
    }

    @Test
    void ignores_duplicate_inventory_event() {
        KafkaEventEnvelope<InventoryReservedPayload> event = inventoryReserved();

        handler.handleInventoryReserved(event);
        handler.handleInventoryReserved(event);

        assertEquals(1, queryInt("select count(*) from processed_events where event_id = '" + INVENTORY_EVENT_ID + "'"));
        assertEquals(1, queryInt("select count(*) from outbox_events"));
    }

    private KafkaEventEnvelope<InventoryReservedPayload> inventoryReserved() {
        return new KafkaEventEnvelope<>(
            INVENTORY_EVENT_ID,
            "InventoryReserved",
            1,
            "inventory-reservation",
            "ord_saga_001",
            "corr-saga-001",
            null,
            "idem-saga-001",
            Instant.parse("2026-05-12T16:00:00Z"),
            "inventory-service",
            new InventoryReservedPayload(
                "ord_saga_001",
                List.of(new InventoryReservedItemPayload("LIMITED-001", "SKU-001", 2)),
                Instant.parse("2026-05-12T16:00:00Z")
            )
        );
    }

    private KafkaEventEnvelope<InventoryReservationFailedPayload> inventoryReservationFailed() {
        return new KafkaEventEnvelope<>(
            UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7c04"),
            "InventoryReservationFailed",
            1,
            "inventory-reservation",
            "ord_saga_001",
            "corr-saga-001",
            null,
            "idem-saga-001",
            Instant.parse("2026-05-12T16:00:00Z"),
            "inventory-service",
            new InventoryReservationFailedPayload(
                "ord_saga_001",
                "INSUFFICIENT_STOCK",
                Instant.parse("2026-05-12T16:00:00Z")
            )
        );
    }

    private KafkaEventEnvelope<PaymentAuthorizedPayload> paymentAuthorized() {
        return new KafkaEventEnvelope<>(
            PAYMENT_EVENT_ID,
            "PaymentAuthorized",
            1,
            "payment",
            "ord_saga_001",
            "corr-saga-001",
            INVENTORY_EVENT_ID,
            "idem-saga-001",
            Instant.parse("2026-05-12T16:01:00Z"),
            "payment-service",
            new PaymentAuthorizedPayload(
                UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7c03"),
                "ord_saga_001",
                new BigDecimal("24000.00"),
                "CARD",
                Instant.parse("2026-05-12T16:01:00Z")
            )
        );
    }

    private KafkaEventEnvelope<PaymentAuthorizationFailedPayload> paymentAuthorizationFailed() {
        return new KafkaEventEnvelope<>(
            UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7c05"),
            "PaymentAuthorizationFailed",
            1,
            "payment",
            "ord_saga_001",
            "corr-saga-001",
            INVENTORY_EVENT_ID,
            "idem-saga-001",
            Instant.parse("2026-05-12T16:01:00Z"),
            "payment-service",
            new PaymentAuthorizationFailedPayload(
                "ord_saga_001",
                new BigDecimal("24000.00"),
                "CARD",
                "PAYMENT_DECLINED",
                Instant.parse("2026-05-12T16:01:00Z")
            )
        );
    }

    private int queryInt(String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }

    private String queryString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }
}
