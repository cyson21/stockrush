package com.stockrush.payment.application;

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
