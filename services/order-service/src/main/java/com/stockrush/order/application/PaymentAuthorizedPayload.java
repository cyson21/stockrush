// PaymentAuthorizedPayload: 도메인 간 전달을 위한 데이터 스냅샷/페이로드 타입입니다.

package com.stockrush.order.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentAuthorizedPayload(
    UUID paymentId,
    String orderId,
    BigDecimal amount,
    String method,
    Instant authorizedAt
) {
}
