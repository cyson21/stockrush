// OrderSagaSnapshot: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

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
