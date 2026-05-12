package com.stockrush.order.application;

import com.stockrush.order.domain.OrderStatus;
import com.stockrush.order.domain.SagaStatus;
import java.math.BigDecimal;
import java.util.List;

public record OrderDetailSnapshot(
    String orderId,
    String memberId,
    OrderStatus status,
    SagaStatus sagaStatus,
    String paymentMethod,
    BigDecimal totalAmount,
    List<OrderDetailItemSnapshot> items
) {
    public OrderDetailSnapshot {
        items = List.copyOf(items);
    }
}
