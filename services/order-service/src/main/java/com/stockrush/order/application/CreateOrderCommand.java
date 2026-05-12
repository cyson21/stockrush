package com.stockrush.order.application;

import java.util.List;

public record CreateOrderCommand(
    String memberId,
    String idempotencyKey,
    String correlationId,
    String paymentMethod,
    List<CreateOrderItemCommand> items
) {

    public CreateOrderCommand(
        String memberId,
        String idempotencyKey,
        String correlationId,
        List<CreateOrderItemCommand> items
    ) {
        this(memberId, idempotencyKey, correlationId, "CARD", items);
    }
}
