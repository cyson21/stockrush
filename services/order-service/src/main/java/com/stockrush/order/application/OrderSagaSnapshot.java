package com.stockrush.order.application;

import com.stockrush.order.domain.OrderStatus;
import com.stockrush.order.domain.SagaStatus;
import java.time.Instant;

public record OrderSagaSnapshot(
    String orderId,
    OrderStatus orderStatus,
    SagaStatus sagaStatus,
    Instant failedAt,
    String businessReason,
    String technicalErrorMessage,
    String lastEventType,
    int outboxAttempts
) {
}
