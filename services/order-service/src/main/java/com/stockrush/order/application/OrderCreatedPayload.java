package com.stockrush.order.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderCreatedPayload(
    String orderId,
    String memberId,
    List<OrderCreatedItemPayload> items,
    BigDecimal totalAmount,
    String couponCode,
    BigDecimal discountAmount,
    BigDecimal payableAmount,
    Instant createdAt
) {
    public OrderCreatedPayload(String orderId, String memberId, List<OrderCreatedItemPayload> items, BigDecimal totalAmount, Instant createdAt) {
        this(orderId, memberId, items, totalAmount, null, BigDecimal.ZERO, totalAmount, createdAt);
    }

    public OrderCreatedPayload {
        items = List.copyOf(items);
    }
}
