package com.stockrush.payment.application;

import java.math.BigDecimal;
import java.time.Instant;
/**
 * 이벤트·명령의 입력/출력 형식을 고정하는 페이로드 DTO로, 계약 안정성과 역직렬화 안정성을 돕습니다.
 */


public record PaymentAuthorizationFailedPayload(
    String orderId,
    BigDecimal amount,
    String method,
    String reason,
    Instant failedAt
) {
}
