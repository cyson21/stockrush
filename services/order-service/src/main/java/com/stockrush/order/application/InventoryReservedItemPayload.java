package com.stockrush.order.application;

public record InventoryReservedItemPayload(
    String productCode,
    String skuId,
    int quantity
) {
}
