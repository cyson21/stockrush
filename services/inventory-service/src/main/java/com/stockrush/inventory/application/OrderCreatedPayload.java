package com.stockrush.inventory.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderCreatedPayload(
    String orderId,
    String memberId,
    List<OrderCreatedItemPayload> items,
    BigDecimal totalAmount,
    Instant createdAt
) {
    public OrderCreatedPayload {
        items = List.copyOf(items);
    }
}
