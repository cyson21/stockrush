// CreateOrderServiceTest: 비즈니스 핵심 흐름을 조합해 상태 변경과 유효성 규칙을 적용합니다.

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
        assertEquals("CARD", result.order().paymentMethod());
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
    void creates_order_with_requested_payment_method() {
        CreateOrderService service = newService();
        CreateOrderCommand command = new CreateOrderCommand(
            "member-1",
            "idem-001",
            "corr-001",
            "FAIL_CARD",
            List.of(new CreateOrderItemCommand("LIMITED-001", "SKU-001", 1, new BigDecimal("12000.00")))
        );

        CreateOrderResult result = service.create(command);

        assertEquals("FAIL_CARD", result.order().paymentMethod());
    }

    @Test
    void applies_coupon_quote_to_payable_amount() {
        RecordingCouponQuoteClient couponQuoteClient = new RecordingCouponQuoteClient(
            new CouponQuoteResult("WELCOME10", true, new BigDecimal("5000.00"), new BigDecimal("24000.00"), null)
        );
        CreateOrderService service = new CreateOrderService(
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> EVENT_ID,
            () -> "ord_20260512_000001",
            couponQuoteClient
        );
        CreateOrderCommand command = new CreateOrderCommand(
            "member-1",
            "idem-001",
            "corr-001",
            "CARD",
            "WELCOME10",
            List.of(
                new CreateOrderItemCommand("LIMITED-001", "SKU-001", 2, new BigDecimal("12000.00")),
                new CreateOrderItemCommand("LIMITED-002", "SKU-002", 1, new BigDecimal("5000.00"))
            )
        );

        CreateOrderResult result = service.create(command);

        assertEquals("WELCOME10", couponQuoteClient.couponCode);
        assertEquals(new BigDecimal("29000.00"), couponQuoteClient.orderAmount);
        assertEquals("corr-001", couponQuoteClient.correlationId);
        assertEquals("WELCOME10", result.order().couponCode());
        assertEquals(new BigDecimal("5000.00"), result.order().discountAmount());
        assertEquals(new BigDecimal("24000.00"), result.order().payableAmount());
        assertEquals("WELCOME10", result.outboxEvent().payload().couponCode());
        assertEquals(new BigDecimal("5000.00"), result.outboxEvent().payload().discountAmount());
        assertEquals(new BigDecimal("24000.00"), result.outboxEvent().payload().payableAmount());
    }

    @Test
    void rejects_order_when_coupon_quote_is_not_applied() {
        CreateOrderService service = new CreateOrderService(
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> EVENT_ID,
            () -> "ord_20260512_000001",
            new RecordingCouponQuoteClient(
                new CouponQuoteResult("EXPIRED10", false, BigDecimal.ZERO, new BigDecimal("29000.00"), "COUPON_OUT_OF_PERIOD")
            )
        );
        CreateOrderCommand command = new CreateOrderCommand(
            "member-1",
            "idem-001",
            "corr-001",
            "CARD",
            "EXPIRED10",
            List.of(
                new CreateOrderItemCommand("LIMITED-001", "SKU-001", 2, new BigDecimal("12000.00")),
                new CreateOrderItemCommand("LIMITED-002", "SKU-002", 1, new BigDecimal("5000.00"))
            )
        );

        CouponNotApplicableException error = assertThrows(CouponNotApplicableException.class, () -> service.create(command));

        assertEquals("Coupon could not be applied: COUPON_OUT_OF_PERIOD", error.getMessage());
    }

    @Test
    void rejects_coupon_quote_when_amounts_are_not_consistent() {
        CreateOrderService service = new CreateOrderService(
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> EVENT_ID,
            () -> "ord_20260512_000001",
            new RecordingCouponQuoteClient(
                new CouponQuoteResult("BROKEN10", true, new BigDecimal("6000.00"), new BigDecimal("20000.00"), "APPLIED")
            )
        );
        CreateOrderCommand command = new CreateOrderCommand(
            "member-1",
            "idem-001",
            "corr-001",
            "CARD",
            "BROKEN10",
            List.of(
                new CreateOrderItemCommand("LIMITED-001", "SKU-001", 2, new BigDecimal("12000.00")),
                new CreateOrderItemCommand("LIMITED-002", "SKU-002", 1, new BigDecimal("5000.00"))
            )
        );

        CouponQuoteUnavailableException error = assertThrows(CouponQuoteUnavailableException.class, () -> service.create(command));

        assertEquals("Coupon quote amount is inconsistent.", error.getMessage());
    }

    @Test
    void rejects_order_without_items() {
        CreateOrderService service = newService();
        CreateOrderCommand command = new CreateOrderCommand("member-1", "idem-001", "corr-001", "CARD", List.of());

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
            "CARD",
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
            "CARD",
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
            "CARD",
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
            "CARD",
            List.of(new CreateOrderItemCommand("LIMITED-001", "SKU-001", 1, new BigDecimal("12000.00")))
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.create(command));

        assertEquals("idempotency key must not be blank", error.getMessage());
    }

    private CreateOrderService newService() {
        return new CreateOrderService(Clock.fixed(NOW, ZoneOffset.UTC), () -> EVENT_ID, () -> "ord_20260512_000001");
    }

    private static class RecordingCouponQuoteClient implements CouponQuoteClient {

        private final CouponQuoteResult result;
        private String couponCode;
        private BigDecimal orderAmount;
        private String correlationId;

        private RecordingCouponQuoteClient(CouponQuoteResult result) {
            this.result = result;
        }

        @Override
        public CouponQuoteResult quote(String couponCode, BigDecimal orderAmount, String correlationId) {
            this.couponCode = couponCode;
            this.orderAmount = orderAmount;
            this.correlationId = correlationId;
            return result;
        }
    }
}
