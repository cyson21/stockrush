package com.stockrush.order.application;

import com.stockrush.order.domain.OrderStatus;
import com.stockrush.order.domain.SagaStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record OrderSummarySnapshot(
    String orderId,
    String memberId,
    OrderStatus status,
    SagaStatus sagaStatus,
    String paymentMethod,
    BigDecimal totalAmount,
    int itemCount,
    Instant createdAt,
    Instant updatedAt
) {
}
