package com.stockrush.inventory.application;

import java.time.Instant;

public record OrderConfirmedPayload(
    String orderId,
    Instant confirmedAt
) {
}
