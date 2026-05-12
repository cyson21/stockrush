package com.stockrush.order.application;

import java.math.BigDecimal;

public record CreateOrderItemCommand(
    String productCode,
    String skuId,
    int quantity,
    BigDecimal unitPrice
) {
}

