package com.stockrush.order.application;

import java.time.Instant;

public record PaymentCancelRequestedPayload(
    String orderId,
    String reason,
    Instant requestedAt
) {
}
