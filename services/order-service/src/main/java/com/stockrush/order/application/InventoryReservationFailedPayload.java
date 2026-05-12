package com.stockrush.order.application;

import java.time.Instant;

public record InventoryReservationFailedPayload(
    String orderId,
    String reason,
    Instant failedAt
) {
}
