package com.stockrush.order.application;

import java.util.List;

public record CreateOrderCommand(
    String memberId,
    String idempotencyKey,
    String correlationId,
    String paymentMethod,
    String couponCode,
    List<CreateOrderItemCommand> items
) {

    public CreateOrderCommand(
        String memberId,
        String idempotencyKey,
        String correlationId,
        String paymentMethod,
        List<CreateOrderItemCommand> items
    ) {
        this(memberId, idempotencyKey, correlationId, paymentMethod, null, items);
    }

    public CreateOrderCommand(
        String memberId,
        String idempotencyKey,
        String correlationId,
        List<CreateOrderItemCommand> items
    ) {
        this(memberId, idempotencyKey, correlationId, "CARD", null, items);
    }
}
