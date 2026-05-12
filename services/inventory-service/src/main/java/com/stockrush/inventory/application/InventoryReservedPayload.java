package com.stockrush.inventory.application;

import java.time.Instant;
import java.util.List;

public record InventoryReservedPayload(
    String orderId,
    List<InventoryReservedItemPayload> items,
    Instant reservedAt
) {
    public InventoryReservedPayload {
        items = List.copyOf(items);
    }
}
