package com.stockrush.catalog.application;

import java.math.BigDecimal;

public record ProductSnapshot(
    String productCode,
    String name,
    String status,
    BigDecimal listPrice
) {
}
