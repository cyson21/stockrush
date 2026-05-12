package com.stockrush.payment.application;

import java.math.BigDecimal;

public record PaymentAuthorizationRequestedPayload(
    String orderId,
    BigDecimal amount,
    String method
) {
}
