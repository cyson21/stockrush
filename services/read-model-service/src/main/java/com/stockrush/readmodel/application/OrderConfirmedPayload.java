package com.stockrush.readmodel.application;

import java.time.Instant;

public record OrderConfirmedPayload(
    String orderId,
    Instant confirmedAt
) {
}
