package com.stockrush.order.application;

public record CreateOrderResult(
    OrderSnapshot order,
    OutboxEventRecord<OrderCreatedPayload> outboxEvent,
    boolean replayed
) {

    public CreateOrderResult(OrderSnapshot order, OutboxEventRecord<OrderCreatedPayload> outboxEvent) {
        this(order, outboxEvent, false);
    }

    public static CreateOrderResult replayed(OrderSnapshot order) {
        return new CreateOrderResult(order, null, true);
    }
}
