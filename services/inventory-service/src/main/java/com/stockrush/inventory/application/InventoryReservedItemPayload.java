package com.stockrush.inventory.application;

public record InventoryReservedItemPayload(
    String productCode,
    String skuId,
    int quantity
) {
}
