package com.stockrush.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.stockrush.order.domain.OrderStatus;
import com.stockrush.order.domain.SagaStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreateOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-12T10:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("018f8d0b-8d32-7c42-9f1b-78328e0f7a11");

    @Test
    void creates_order_and_outbox_event_with_common_envelope() {
        CreateOrderService service = new CreateOrderService(
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> EVENT_ID,
            () -> "ord_20260512_000001"
        );
        CreateOrderCommand command = new CreateOrderCommand(
            "member-1",
            "idem-001",
            "corr-001",
            List.of(
                new CreateOrderItemCommand("LIMITED-001", "SKU-001", 2, new BigDecimal("12000.00")),
                new CreateOrderItemCommand("LIMITED-002", "SKU-002", 1, new BigDecimal("5000.00"))
            )
        );

        CreateOrderResult result = service.create(command);

        assertEquals("ord_20260512_000001", result.order().orderId());
        assertEquals(OrderStatus.CREATED, result.order().status());
        assertEquals(SagaStatus.STARTED, result.order().sagaStatus());
        assertEquals(new BigDecimal("29000.00"), result.order().totalAmount());
        assertEquals(NOW, result.order().createdAt());

        OutboxEventRecord<OrderCreatedPayload> event = result.outboxEvent();
        assertEquals(EVENT_ID, event.eventId());
        assertEquals("OrderCreated", event.eventType());
        assertEquals(1, event.eventVersion());
        assertEquals("order", event.aggregateType());
        assertEquals("ord_20260512_000001", event.aggregateId());
        assertEquals("corr-001", event.correlationId());
        assertEquals("idem-001", event.idempotencyKey());
        assertEquals(NOW, event.occurredAt());
        assertEquals("order-service", event.sourceService());
        assertEquals(OutboxEventStatus.PENDING, event.status());
        assertEquals("stockrush.order.events.v1", event.topic());
        assertEquals("ord_20260512_000001", event.partitionKey());
        assertEquals("ord_20260512_000001", event.payload().orderId());
        assertEquals("member-1", event.payload().memberId());
        assertEquals(new BigDecimal("29000.00"), event.payload().totalAmount());
        assertEquals(2, event.payload().items().size());
    }

    @Test
    void rejects_order_without_items() {
        CreateOrderService service = newService();
        CreateOrderCommand command = new CreateOrderCommand("member-1", "idem-001", "corr-001", List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.create(command));

        assertEquals("order items must not be empty", error.getMessage());
    }

    @Test
    void rejects_non_positive_quantity() {
        CreateOrderService service = newService();
        CreateOrderCommand command = new CreateOrderCommand(
            "member-1",
            "idem-001",
            "corr-001",
            List.of(new CreateOrderItemCommand("LIMITED-001", "SKU-001", 0, new BigDecimal("12000.00")))
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.create(command));

        assertEquals("order item quantity must be positive", error.getMessage());
    }

    @Test
    void rejects_non_positive_unit_price() {
        CreateOrderService service = newService();
        CreateOrderCommand command = new CreateOrderCommand(
            "member-1",
            "idem-001",
            "corr-001",
            List.of(new CreateOrderItemCommand("LIMITED-001", "SKU-001", 1, BigDecimal.ZERO))
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.create(command));

        assertEquals("order item unit price must be positive", error.getMessage());
    }

    @Test
    void rejects_blank_member_id() {
        CreateOrderService service = newService();
        CreateOrderCommand command = new CreateOrderCommand(
            " ",
            "idem-001",
            "corr-001",
            List.of(new CreateOrderItemCommand("LIMITED-001", "SKU-001", 1, new BigDecimal("12000.00")))
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.create(command));

        assertEquals("member id must not be blank", error.getMessage());
    }

    @Test
    void rejects_blank_idempotency_key() {
        CreateOrderService service = newService();
        CreateOrderCommand command = new CreateOrderCommand(
            "member-1",
            " ",
            "corr-001",
            List.of(new CreateOrderItemCommand("LIMITED-001", "SKU-001", 1, new BigDecimal("12000.00")))
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.create(command));

        assertEquals("idempotency key must not be blank", error.getMessage());
    }

    private CreateOrderService newService() {
        return new CreateOrderService(Clock.fixed(NOW, ZoneOffset.UTC), () -> EVENT_ID, () -> "ord_20260512_000001");
    }
}
