package com.stockrush.order.application;

import java.math.BigDecimal;

public record OrderCreatedItemPayload(
    String productCode,
    String skuId,
    int quantity,
    BigDecimal unitPrice
) {
}

