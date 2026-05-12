package com.stockrush.order.application;

import java.math.BigDecimal;

public record OrderLineSnapshot(
    String productCode,
    String skuId,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal lineAmount
) {
}

