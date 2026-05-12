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

    public CreateOrderService(Clock clock, Supplier<UUID> eventIdSupplier, OrderIdGenerator orderIdGenerator) {
        this.clock = clock;
        this.eventIdSupplier = eventIdSupplier;
        this.orderIdGenerator = orderIdGenerator;
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

        OrderSnapshot order = new OrderSnapshot(
            orderId,
            command.memberId(),
            OrderStatus.CREATED,
            SagaStatus.STARTED,
            totalAmount,
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
        OutboxEventRecord outboxEvent = new OutboxEventRecord(
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
}
