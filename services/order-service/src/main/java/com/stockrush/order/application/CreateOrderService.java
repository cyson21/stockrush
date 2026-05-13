package com.stockrush.order.application;

import com.stockrush.order.domain.OrderStatus;
import com.stockrush.order.domain.SagaStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class CreateOrderService {

    private static final String ORDER_EVENT_TOPIC = "stockrush.order.events.v1";
    private final Clock clock;
    private final Supplier<UUID> eventIdSupplier;
    private final OrderIdGenerator orderIdGenerator;
    private final CouponQuoteClient couponQuoteClient;

    public CreateOrderService(Clock clock, Supplier<UUID> eventIdSupplier, OrderIdGenerator orderIdGenerator) {
        this(
            clock,
            eventIdSupplier,
            orderIdGenerator,
            (couponCode, orderAmount, correlationId) -> {
                throw new CouponQuoteUnavailableException("Coupon quote client is not configured.");
            }
        );
    }

    public CreateOrderService(
        Clock clock,
        Supplier<UUID> eventIdSupplier,
        OrderIdGenerator orderIdGenerator,
        CouponQuoteClient couponQuoteClient
    ) {
        this.clock = clock;
        this.eventIdSupplier = eventIdSupplier;
        this.orderIdGenerator = orderIdGenerator;
        this.couponQuoteClient = couponQuoteClient;
    }

    public CreateOrderResult create(CreateOrderCommand command) {
        validate(command);

        String orderId = orderIdGenerator.nextId();
        Instant now = clock.instant();
        List<OrderLineSnapshot> lines = command.items().stream()
            .map(this::toLine)
            .toList();
        BigDecimal totalAmount = lines.stream()
            .map(OrderLineSnapshot::lineAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        String paymentMethod = paymentMethod(command.paymentMethod());
        CouponPrice couponPrice = couponPrice(command.couponCode(), totalAmount, command.correlationId());

        OrderSnapshot order = new OrderSnapshot(
            orderId,
            command.memberId(),
            OrderStatus.CREATED,
            SagaStatus.STARTED,
            paymentMethod,
            couponPrice.couponCode(),
            totalAmount,
            couponPrice.discountAmount(),
            couponPrice.payableAmount(),
            now,
            lines
        );
        OrderCreatedPayload payload = new OrderCreatedPayload(
            orderId,
            command.memberId(),
            command.items().stream().map(this::toPayloadItem).toList(),
            totalAmount,
            now
        );
        OutboxEventRecord<OrderCreatedPayload> outboxEvent = new OutboxEventRecord<>(
            eventIdSupplier.get(),
            "OrderCreated",
            1,
            "order",
            orderId,
            command.correlationId(),
            null,
            command.idempotencyKey(),
            now,
            "order-service",
            OutboxEventStatus.PENDING,
            ORDER_EVENT_TOPIC,
            orderId,
            payload
        );

        return new CreateOrderResult(order, outboxEvent);
    }

    private OrderLineSnapshot toLine(CreateOrderItemCommand item) {
        BigDecimal lineAmount = item.unitPrice().multiply(BigDecimal.valueOf(item.quantity()));
        return new OrderLineSnapshot(item.productCode(), item.skuId(), item.quantity(), item.unitPrice(), lineAmount);
    }

    private OrderCreatedItemPayload toPayloadItem(CreateOrderItemCommand item) {
        return new OrderCreatedItemPayload(item.productCode(), item.skuId(), item.quantity(), item.unitPrice());
    }

    private void validate(CreateOrderCommand command) {
        if (isBlank(command.memberId())) {
            throw new IllegalArgumentException("member id must not be blank");
        }
        if (isBlank(command.idempotencyKey())) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
        if (command.items() == null || command.items().isEmpty()) {
            throw new IllegalArgumentException("order items must not be empty");
        }
        if (command.paymentMethod() != null && command.paymentMethod().isBlank()) {
            throw new IllegalArgumentException("payment method must not be blank");
        }
        for (CreateOrderItemCommand item : command.items()) {
            if (item.quantity() <= 0) {
                throw new IllegalArgumentException("order item quantity must be positive");
            }
            if (item.unitPrice() == null || item.unitPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("order item unit price must be positive");
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String paymentMethod(String paymentMethod) {
        if (paymentMethod == null) {
            return "CARD";
        }
        return paymentMethod.trim();
    }

    private CouponPrice couponPrice(String rawCouponCode, BigDecimal totalAmount, String correlationId) {
        String couponCode = normalizeCouponCode(rawCouponCode);
        if (couponCode == null) {
            return new CouponPrice(null, BigDecimal.ZERO, totalAmount);
        }

        CouponQuoteResult quote = couponQuoteClient.quote(couponCode, totalAmount, correlationId);
        if (!quote.applied()) {
            throw new CouponNotApplicableException(quote.reason());
        }
        if (quote.discountAmount() == null || quote.discountAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("coupon discount amount must not be negative");
        }
        if (quote.payAmount() == null || quote.payAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("coupon pay amount must not be negative");
        }
        if (quote.payAmount().compareTo(totalAmount) > 0) {
            throw new IllegalArgumentException("coupon pay amount must not exceed order amount");
        }
        if (quote.payAmount().add(quote.discountAmount()).compareTo(totalAmount) != 0) {
            throw new CouponQuoteUnavailableException("Coupon quote amount is inconsistent.");
        }

        return new CouponPrice(couponCode, quote.discountAmount(), quote.payAmount());
    }

    private String normalizeCouponCode(String couponCode) {
        if (couponCode == null || couponCode.isBlank()) {
            return null;
        }
        return couponCode.trim();
    }

    private record CouponPrice(String couponCode, BigDecimal discountAmount, BigDecimal payableAmount) {
    }
}
