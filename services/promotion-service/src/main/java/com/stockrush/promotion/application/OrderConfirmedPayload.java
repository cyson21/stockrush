package com.stockrush.promotion.application;

import java.time.Instant;

public record OrderConfirmedPayload(
    String orderId,
    Instant confirmedAt
) {
}
