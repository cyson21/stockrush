package com.stockrush.order.application;

import java.math.BigDecimal;

public record PaymentAuthorizationRequestedPayload(
    String orderId,
    BigDecimal amount,
    String method
) {
}
