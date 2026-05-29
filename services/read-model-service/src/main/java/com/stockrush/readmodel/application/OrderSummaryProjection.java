// OrderSummaryProjection: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

package com.stockrush.readmodel.application;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderSummaryProjection(
    String orderId,
    String memberId,
    String status,
    String sagaStatus,
    String couponCode,
    BigDecimal totalAmount,
    BigDecimal discountAmount,
    BigDecimal payableAmount,
    int itemCount,
    String cancellationReason,
    Instant createdAt,
    Instant updatedAt
) {
}
