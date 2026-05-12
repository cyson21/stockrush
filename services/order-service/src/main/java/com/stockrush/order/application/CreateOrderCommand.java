package com.stockrush.order.application;

import java.util.List;

public record CreateOrderCommand(
    String memberId,
    String idempotencyKey,
    String correlationId,
    List<CreateOrderItemCommand> items
) {
}

