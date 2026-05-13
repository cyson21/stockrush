package com.stockrush.order.application;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentAuthorizationDelayedPayload(
    String orderId,
    BigDecimal amount,
    String method,
    String reason,
    Instant delayedAt
) {
}
