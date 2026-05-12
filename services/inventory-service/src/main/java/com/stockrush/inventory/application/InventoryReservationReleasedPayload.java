package com.stockrush.inventory.application;

import java.time.Instant;
import java.util.List;

public record InventoryReservationReleasedPayload(
    String orderId,
    String reason,
    List<InventoryReservedItemPayload> items,
    Instant releasedAt
) {
    public InventoryReservationReleasedPayload {
        items = List.copyOf(items);
    }
}
