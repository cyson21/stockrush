package com.stockrush.promotion.application;

import java.time.Instant;

public record OrderCancelledPayload(
    String orderId,
    String reason,
    Instant cancelledAt
) {
}
