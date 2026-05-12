package com.stockrush.order.application;

import java.util.List;

public record OrderPageSnapshot(
    int page,
    int size,
    List<OrderSummarySnapshot> items
) {
    public OrderPageSnapshot {
        items = List.copyOf(items);
    }
}
