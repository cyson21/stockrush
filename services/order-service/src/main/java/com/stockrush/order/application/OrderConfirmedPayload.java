package com.stockrush.order.application;

import java.time.Instant;

public record OrderConfirmedPayload(
    String orderId,
    Instant confirmedAt
) {
}
