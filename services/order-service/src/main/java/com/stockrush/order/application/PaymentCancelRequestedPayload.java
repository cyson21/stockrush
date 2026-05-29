// PaymentCancelRequestedPayload: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

package com.stockrush.order.application;

import java.time.Instant;

public record PaymentCancelRequestedPayload(
    String orderId,
    String reason,
    Instant requestedAt
) {
}
