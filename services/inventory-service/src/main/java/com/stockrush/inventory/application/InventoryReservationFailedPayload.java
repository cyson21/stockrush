package com.stockrush.inventory.application;

import java.time.Instant;

public record InventoryReservationFailedPayload(
    String orderId,
    String reason,
    Instant failedAt
) {
}
