package com.stockrush.order.application;

import com.stockrush.order.domain.OrderStatus;
import com.stockrush.order.domain.SagaStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderSnapshot(
    String orderId,
    String memberId,
    OrderStatus status,
    SagaStatus sagaStatus,
    String paymentMethod,
    BigDecimal totalAmount,
    Instant createdAt,
    List<OrderLineSnapshot> items
) {
    public OrderSnapshot {
        items = List.copyOf(items);
    }
}
