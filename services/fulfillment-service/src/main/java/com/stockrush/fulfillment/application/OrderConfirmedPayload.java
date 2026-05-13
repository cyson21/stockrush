package com.stockrush.fulfillment.application;

import java.time.Instant;

public record OrderConfirmedPayload(
    String orderId,
    Instant confirmedAt
) {
}
