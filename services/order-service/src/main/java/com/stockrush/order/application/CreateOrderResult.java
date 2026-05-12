package com.stockrush.order.application;

public record CreateOrderResult(
    OrderSnapshot order,
    OutboxEventRecord<OrderCreatedPayload> outboxEvent
) {
}
