package com.stockrush.inventory.application;

public record StockSnapshot(
    String skuId,
    String productCode,
    int availableQuantity,
    int reservedQuantity,
    long version
) {
}
