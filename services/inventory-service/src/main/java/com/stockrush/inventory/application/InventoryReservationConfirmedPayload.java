package com.stockrush.inventory.application;

import java.time.Instant;
import java.util.List;

public record InventoryReservationConfirmedPayload(
    String orderId,
    List<InventoryReservedItemPayload> items,
    Instant confirmedAt
) {
    public InventoryReservationConfirmedPayload {
        items = List.copyOf(items);
    }
}
